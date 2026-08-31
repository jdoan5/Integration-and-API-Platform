"""
The HTTP client the MCP tools call.

Everything an LLM does to the inventory system goes through here, which makes
this the single place to enforce the things an LLM gets wrong: it retries,
it invents arguments, and it will happily call a write twice.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any, Self

import httpx

from .config import Settings
from .config import settings as default_settings

# A fixed namespace so the same intent always hashes to the same key, in this
# process and the next one. uuid4() here would defeat the entire point.
IDEMPOTENCY_NAMESPACE = uuid.UUID("6f9619ff-8b86-d011-b42d-00c04fc964ff")

CORRELATION_HEADER = "X-Correlation-ID"


class InventoryError(Exception):
    """A tool-visible failure.

    The message is written for a model to read and act on, not for a log file.
    A bare 400 teaches the model nothing; "SKU must look like ELEC-LAP-001"
    gets a corrected retry on the next turn.
    """

    def __init__(self, message: str, *, status: int | None = None, correlation_id: str | None = None):
        super().__init__(message)
        self.status = status
        self.correlation_id = correlation_id


def movement_idempotency_key(
    session_id: str,
    sku: str,
    warehouse_code: str,
    movement_type: str,
    quantity: int,
) -> str:
    """Derive a stable Idempotency-Key from the *intent*, not the attempt.

    This is the crux of letting a model near a write endpoint. A random key per
    call would make every retry a NEW movement and silently double-count stock.
    Deriving the key from the arguments means a model that calls the same tool
    twice in one session gets `replayed: true` and the stock moves once.

    The session id is in the hash on purpose: recording the same movement again
    tomorrow is a legitimate business event, not a retry.
    """
    seed = f"{session_id}:{sku}:{warehouse_code}:{movement_type}:{quantity}"
    return str(uuid.uuid5(IDEMPOTENCY_NAMESPACE, seed))


@dataclass
class InventoryClient:
    """Talks to the Phase 2 REST facade through the Phase 3 gateway."""

    settings: Settings = default_settings
    correlation_id: str = ""
    _client: httpx.AsyncClient | None = None

    def __post_init__(self) -> None:
        if not self.correlation_id:
            # Kong's correlation-id plugin only generates an ID when the caller
            # did not send one. Sending ours means the trace STARTS at the model
            # and the same ID spans agent -> Kong -> REST -> SOAP -> Postgres.
            self.correlation_id = f"mcp-{uuid.uuid4()}"

    async def __aenter__(self) -> Self:
        self._client = httpx.AsyncClient(
            timeout=self.settings.timeout_seconds,
            headers={
                self.settings.api_key_header: self.settings.api_key,
                "X-Client-Id": self.settings.client_id,
                CORRELATION_HEADER: self.correlation_id,
            },
        )
        return self

    async def __aexit__(self, *exc: object) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    # ------------------------------------------------------------------
    @property
    def http(self) -> httpx.AsyncClient:
        if self._client is None:
            raise RuntimeError("InventoryClient used outside its async context")
        return self._client

    async def _request(self, method: str, url: str, **kwargs: Any) -> Any:
        try:
            response = await self.http.request(method, url, **kwargs)
        except httpx.ConnectError as exc:
            raise InventoryError(
                f"Cannot reach the inventory platform at {url}. Is the gateway "
                f"running? `docker compose up -d redis kong` and start the REST "
                f"facade on 8082.",
                correlation_id=self.correlation_id,
            ) from exc
        except httpx.TimeoutException as exc:
            raise InventoryError(
                f"Request to {url} timed out after {self.settings.timeout_seconds}s.",
                correlation_id=self.correlation_id,
            ) from exc

        self._raise_for_status(response)
        if not response.content:
            return None
        return response.json()

    def _raise_for_status(self, response: httpx.Response) -> None:
        if response.is_success:
            return

        # Kong answers 401 itself; the request never reached the application.
        # Saying so stops the model "fixing" a perfectly valid SKU.
        detail = {
            401: "Rejected by the API gateway: the apikey header is missing or wrong. "
                 "This is a configuration problem, not a bad argument - do not retry with different arguments.",
            403: "The gateway accepted the key but the consumer is not allowed on this route.",
            404: "No such record. Check the SKU exists before recording movements against it.",
            429: "Rate limited by the gateway (20 requests/minute for this consumer). "
                 "Wait before retrying; do not fan out more calls.",
            503: "The upstream SOAP service is unreachable. The facade is up but its backend is not.",
            # 502 is what a malformed SKU produces on /stock/{sku}, because that
            # path has no local validation and the XSD rejects it upstream. The
            # status alone would send a model looking for an outage.
            502: "The upstream SOAP service rejected the request. Usually a malformed SKU or "
                 "warehouse code that this endpoint does not validate locally: check the value "
                 "against the vocabulary resource before retrying.",
            # /low-stock does not translate upstream faults at all, so anything
            # wrong there surfaces as a bare 500.
            500: "The service failed while handling the request. If this was a low-stock query, "
                 "the cause is usually an invalid warehouse filter. Retry without it.",
        }.get(response.status_code)

        if detail is None:
            body = response.text.strip()
            detail = f"HTTP {response.status_code}: {body[:400] or 'no response body'}"

        raise InventoryError(
            detail,
            status=response.status_code,
            correlation_id=response.headers.get(CORRELATION_HEADER, self.correlation_id),
        )

    # ------------------------------------------------------------------
    # Reads
    async def get_product(self, sku: str) -> dict[str, Any]:
        return await self._request("GET", f"{self.settings.rest_root}/products/{sku}")

    async def get_stock(self, sku: str, warehouse: str | None = None) -> list[dict[str, Any]]:
        params = {"warehouse": warehouse} if warehouse else None
        return await self._request("GET", f"{self.settings.rest_root}/stock/{sku}", params=params)

    async def list_low_stock(self, warehouse: str | None = None, limit: int = 50) -> dict[str, Any]:
        params: dict[str, Any] = {"limit": limit}
        if warehouse:
            params["warehouse"] = warehouse
        return await self._request("GET", f"{self.settings.rest_root}/low-stock", params=params)

    async def cache_stats(self) -> dict[str, Any]:
        return await self._request("GET", f"{self.settings.rest_root}/_cache/stats")

    async def pipeline_status(self) -> dict[str, Any]:
        return await self._request("GET", f"{self.settings.events_url}/events/status")

    async def daily_totals(self) -> list[dict[str, Any]]:
        return await self._request("GET", f"{self.settings.events_url}/events/daily-totals")

    # ------------------------------------------------------------------
    # The write
    async def record_movement(
        self,
        *,
        sku: str,
        warehouse_code: str,
        movement_type: str,
        quantity: int,
        reference_type: str | None = None,
        notes: str | None = None,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "sku": sku,
            "warehouseCode": warehouse_code,
            "movementType": movement_type,
            "quantity": quantity,
        }
        if reference_type:
            payload["referenceType"] = reference_type
        if notes:
            payload["notes"] = notes

        return await self._request(
            "POST",
            f"{self.settings.rest_root}/movements",
            json=payload,
            headers={"Idempotency-Key": idempotency_key},
        )
