"""The HTTP client: headers, error translation, and the idempotency key."""

from __future__ import annotations

import httpx
import pytest
import respx

from inventory_mcp.client import (
    CORRELATION_HEADER,
    InventoryClient,
    InventoryError,
    movement_idempotency_key,
)
from inventory_mcp.config import settings

PRODUCT_URL = f"{settings.rest_root}/products/ELEC-LAP-001"
MOVEMENTS_URL = f"{settings.rest_root}/movements"


class TestIdempotencyKey:
    def test_same_intent_gives_the_same_key(self):
        """A retry must reuse the key, or the movement is counted twice."""
        args = ("session-a", "ELEC-LAP-001", "WH-EAST", "OUT", 25)
        assert movement_idempotency_key(*args) == movement_idempotency_key(*args)

    def test_different_session_gives_a_different_key(self):
        """Recording the same movement tomorrow is a real event, not a retry."""
        first = movement_idempotency_key("session-a", "ELEC-LAP-001", "WH-EAST", "OUT", 25)
        second = movement_idempotency_key("session-b", "ELEC-LAP-001", "WH-EAST", "OUT", 25)
        assert first != second

    def test_quantity_change_gives_a_different_key(self):
        base = movement_idempotency_key("s", "ELEC-LAP-001", "WH-EAST", "OUT", 25)
        other = movement_idempotency_key("s", "ELEC-LAP-001", "WH-EAST", "OUT", 26)
        assert base != other


class TestHeaders:
    @respx.mock
    async def test_gateway_headers_are_sent(self):
        route = respx.get(PRODUCT_URL).mock(
            return_value=httpx.Response(200, json={"sku": "ELEC-LAP-001"})
        )
        async with InventoryClient() as client:
            await client.get_product("ELEC-LAP-001")

        sent = route.calls.last.request.headers
        assert sent[settings.api_key_header] == settings.api_key
        assert sent["X-Client-Id"] == settings.client_id
        # The trace starts at the agent, so Kong adopts our ID rather than
        # generating its own - one ID spans agent -> Kong -> REST -> SOAP.
        assert sent[CORRELATION_HEADER].startswith("mcp-")

    @respx.mock
    async def test_idempotency_key_reaches_the_facade(self):
        route = respx.post(MOVEMENTS_URL).mock(
            return_value=httpx.Response(201, json={"movementId": 1, "replayed": False})
        )
        async with InventoryClient() as client:
            await client.record_movement(
                sku="ELEC-LAP-001",
                warehouse_code="WH-EAST",
                movement_type="OUT",
                quantity=5,
                idempotency_key="fixed-key",
            )
        assert route.calls.last.request.headers["Idempotency-Key"] == "fixed-key"

    @respx.mock
    async def test_movement_body_uses_the_facade_field_names(self):
        """camelCase, matching MovementRequestDto - not the Python snake_case."""
        route = respx.post(MOVEMENTS_URL).mock(return_value=httpx.Response(201, json={}))
        async with InventoryClient() as client:
            await client.record_movement(
                sku="ELEC-LAP-001",
                warehouse_code="WH-EAST",
                movement_type="OUT",
                quantity=5,
                idempotency_key="k",
            )
        import json as _json

        body = _json.loads(route.calls.last.request.content)
        assert set(body) == {"sku", "warehouseCode", "movementType", "quantity"}


class TestErrorTranslation:
    """Errors are written for a model to act on, not for a log file."""

    @respx.mock
    async def test_401_says_do_not_retry(self):
        respx.get(PRODUCT_URL).mock(return_value=httpx.Response(401))
        async with InventoryClient() as client:
            with pytest.raises(InventoryError) as exc:
                await client.get_product("ELEC-LAP-001")
        assert "do not retry" in str(exc.value).lower()
        assert exc.value.status == 401

    @respx.mock
    async def test_429_tells_the_model_to_back_off(self):
        respx.get(PRODUCT_URL).mock(return_value=httpx.Response(429))
        async with InventoryClient() as client:
            with pytest.raises(InventoryError) as exc:
                await client.get_product("ELEC-LAP-001")
        assert "rate limited" in str(exc.value).lower()

    @respx.mock
    async def test_502_is_read_as_bad_input_not_an_outage(self):
        """A malformed SKU on /stock returns 502; the model must not read that
        as "the platform is down" and stop trying."""
        respx.get(PRODUCT_URL).mock(return_value=httpx.Response(502))
        async with InventoryClient() as client:
            with pytest.raises(InventoryError) as exc:
                await client.get_product("ELEC-LAP-001")
        assert "malformed" in str(exc.value).lower()

    @respx.mock
    async def test_500_points_at_the_warehouse_filter(self):
        """/low-stock does not translate upstream faults, so a bad warehouse
        filter arrives as a bare 500."""
        respx.get(PRODUCT_URL).mock(return_value=httpx.Response(500))
        async with InventoryClient() as client:
            with pytest.raises(InventoryError) as exc:
                await client.get_product("ELEC-LAP-001")
        assert "warehouse" in str(exc.value).lower()

    @respx.mock
    async def test_connection_refused_names_the_fix(self):
        respx.get(PRODUCT_URL).mock(side_effect=httpx.ConnectError("refused"))
        async with InventoryClient() as client:
            with pytest.raises(InventoryError) as exc:
                await client.get_product("ELEC-LAP-001")
        assert "docker compose" in str(exc.value)
