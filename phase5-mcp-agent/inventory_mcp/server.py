"""
The MCP server: the inventory platform's fourth contract.

Phase 1 published a contract for machines (XSD/WSDL).
Phase 2 published one for developers (OpenAPI).
Phase 4 published one for streams (Avro, with a registry that refuses breaking changes).
This publishes one for language models (MCP tool schemas, which are JSON Schema).

The difference is the consumer. An XSD consumer is a program someone wrote and
tested. An MCP consumer is a model that improvises: it retries, invents
arguments, and calls the write endpoint twice in a row. Every guard already in
this repo - the XSD's patterns, the facade's Idempotency-Key, Kong's key-auth
and rate limiting - was built for human-written clients and turns out to be
exactly what a non-deterministic one needs.
"""

from __future__ import annotations

import re
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import Context, FastMCP
from mcp.types import ToolAnnotations

from .client import InventoryClient, InventoryError, movement_idempotency_key
from .config import settings
from .tracing import setup_tracing, tool_span

# Constraints copied from the Phase 1 XSD. They are repeated in the tool
# docstrings because a model reads the schema description, not the WSDL - the
# description IS the model's copy of the contract.
SKU_PATTERN = r"[A-Z]{3,4}-[A-Z0-9]{3,5}-?[0-9]{0,5}"
WAREHOUSE_PATTERN = r"WH-[A-Z]{2,4}"
MOVEMENT_TYPES = ("IN", "OUT", "TRANSFER_IN", "TRANSFER_OUT", "ADJUSTMENT", "RETURN")

# The warehouses the companion database actually seeds. The pattern above admits
# far more than exist, so a model that only reads the regex invents plausible
# codes like WH-EAST and gets a 404 it cannot diagnose.
KNOWN_WAREHOUSES = ("WH-WEST", "WH-CENT", "WH-EAST")


def _check_sku(sku: str) -> None:
    """Validate before calling, because not every endpoint validates for us.

    xs:pattern and jakarta @Pattern are both FULL matches, so re.fullmatch is
    the faithful translation - re.match would accept "ELEC-LAP-001-JUNK".
    GET /stock/{sku} has no server-side check at all and turns a bad SKU into a
    502, which reads to a model like an outage rather than its own mistake.
    """
    if not re.fullmatch(SKU_PATTERN, sku):
        raise InventoryError(
            f"{sku!r} is not a valid SKU. It must match {SKU_PATTERN} in full, "
            f"for example ELEC-LAP-001."
        )


def _check_warehouse(code: str) -> None:
    if not re.fullmatch(WAREHOUSE_PATTERN, code):
        raise InventoryError(
            f"{code!r} is not a valid warehouse code. It must match {WAREHOUSE_PATTERN}; "
            f"the warehouses that exist are {', '.join(KNOWN_WAREHOUSES)}."
        )

REPO_ROOT = Path(__file__).resolve().parents[2]
OPENAPI_PATH = REPO_ROOT / "phase2-rest-facade" / "src" / "main" / "resources" / "openapi.yaml"


@dataclass
class ServerContext:
    """Shared across every tool call for the life of the server process."""

    client: InventoryClient
    session_id: str


@asynccontextmanager
async def lifespan(_server: FastMCP) -> AsyncIterator[ServerContext]:
    """One HTTP client and one session id for the whole process.

    The session id seeds every Idempotency-Key, so two identical write calls in
    one session collapse to one movement while the same call tomorrow does not.
    """
    # Before the client is constructed: HTTPXClientInstrumentor patches the
    # class, so a client built earlier would never carry a traceparent.
    setup_tracing()

    session_id = str(uuid.uuid4())
    async with InventoryClient() as client:
        yield ServerContext(client=client, session_id=session_id)


mcp = FastMCP(
    "inventory-platform",
    instructions=(
        "Read and modify a warehouse inventory system through its governed API "
        "gateway. Reads are cheap and safe. Recording a stock movement is a real "
        "write against a real database: confirm the SKU and warehouse exist with "
        "get_product and get_stock BEFORE calling record_movement, and never call "
        "record_movement more than once for the same physical event."
    ),
    lifespan=lifespan,
    host=settings.host,
    port=settings.port,
    # INFO logs every ListTools/CallTool request. On stdio that noise lands in
    # the client's stderr pane and buries the actual tool trace.
    log_level="WARNING",
)


def _ctx(ctx: Context) -> ServerContext:
    return ctx.request_context.lifespan_context


# ---------------------------------------------------------------------------
# READ TOOLS
# ---------------------------------------------------------------------------
@mcp.tool(
    annotations=ToolAnnotations(
        title="Look up a product",
        readOnlyHint=True,
        idempotentHint=True,
        openWorldHint=False,
    )
)
async def get_product(sku: str, ctx: Context) -> dict[str, Any]:
    """Fetch a product's catalog record: name, category, price, cost, and reorder policy.

    Args:
        sku: Product code matching the pattern ``[A-Z]{3,4}-[A-Z0-9]{3,5}-?[0-9]{0,5}``,
            for example ``ELEC-LAP-001``. Anything else is rejected at the boundary.

    Returns the product's reorderPoint and reorderQuantity, which are what you
    need to decide whether a stock level is actually a problem.
    """
    with tool_span("get_product", **{"inventory.sku": sku}):
        _check_sku(sku)
        return await _ctx(ctx).client.get_product(sku)


@mcp.tool(
    annotations=ToolAnnotations(
        title="Check stock levels",
        readOnlyHint=True,
        idempotentHint=True,
        openWorldHint=False,
    )
)
async def get_stock(sku: str, ctx: Context, warehouse: str | None = None) -> list[dict[str, Any]]:
    """Current quantity on hand for a SKU, per warehouse.

    Args:
        sku: Product code, for example ``ELEC-LAP-001``.
        warehouse: Optional warehouse code like ``WH-EAST``. Omit to get every warehouse.

    Each row carries ``belowReorderPoint``, already computed - compare against
    that rather than doing the arithmetic yourself.
    """
    with tool_span("get_stock", **{"inventory.sku": sku, "inventory.warehouse": warehouse}):
        _check_sku(sku)
        if warehouse:
            _check_warehouse(warehouse)

        levels = await _ctx(ctx).client.get_stock(sku, warehouse)

    # An unknown warehouse returns 200 [] rather than 404, which is
    # indistinguishable from "stocked here, quantity zero" - a stock row with
    # quantity 0 really does come back populated. Handing a model a bare []
    # makes it report "no stock at WH-NYC" for a warehouse that does not exist.
    # An error naming both possibilities is the only answer that is not
    # silently wrong.
        if warehouse and not levels:
            raise InventoryError(
                f"No stock rows for {sku} at {warehouse}. Either {warehouse} is not a warehouse "
                f"(the ones that exist are {', '.join(KNOWN_WAREHOUSES)}), or this product is not "
                f"stocked there. Call get_stock without the warehouse filter to see where it is."
            )
        return levels


@mcp.tool(
    annotations=ToolAnnotations(
        title="List low stock",
        readOnlyHint=True,
        idempotentHint=True,
        openWorldHint=False,
    )
)
async def list_low_stock(ctx: Context, warehouse: str | None = None, limit: int = 50) -> dict[str, Any]:
    """Every SKU currently at or below its reorder point.

    Args:
        warehouse: Optional warehouse code like ``WH-EAST``. Omit for all warehouses.
        limit: Maximum rows to return (default 50).

    Start here for "what needs restocking?" questions rather than checking SKUs
    one at a time - one call instead of dozens, and it stays inside the rate limit.
    """
    with tool_span("list_low_stock", **{"inventory.warehouse": warehouse}):
        if warehouse:
            _check_warehouse(warehouse)
        return await _ctx(ctx).client.list_low_stock(warehouse, limit)


@mcp.tool(
    annotations=ToolAnnotations(
        title="Movement history by day",
        readOnlyHint=True,
        idempotentHint=True,
        openWorldHint=False,
    )
)
async def daily_movement_totals(ctx: Context) -> list[dict[str, Any]]:
    """Daily units in/out per SKU and warehouse, from the Kafka read model.

    This is the projection built by the Phase 4 consumer, not a live query - use
    it to answer "what happened to this product recently?" and to explain WHY a
    stock level is where it is.
    """
    with tool_span("daily_movement_totals"):
        return await _ctx(ctx).client.daily_totals()


@mcp.tool(
    annotations=ToolAnnotations(
        title="Platform health",
        readOnlyHint=True,
        idempotentHint=True,
        openWorldHint=False,
    )
)
async def platform_status(ctx: Context) -> dict[str, Any]:
    """Cache hit rates and event-pipeline health (outbox backlog, relay, consumers).

    Useful when data looks stale: a large ``outbox.pending`` count or a high
    ``worstLagSeconds`` means the read model is behind, so a movement may have
    happened that the daily totals do not show yet.
    """
    with tool_span("platform_status"):
        ctxt = _ctx(ctx)
        result: dict[str, Any] = {}
        for name, call in (("cache", ctxt.client.cache_stats),
                           ("events", ctxt.client.pipeline_status)):
            try:
                result[name] = await call()
            except InventoryError as exc:
                # Partial health is more useful than an exception. The events app
                # on 8083 is optional; the cache lives behind the gateway.
                result[name] = {"unavailable": str(exc)}
        return result


# ---------------------------------------------------------------------------
# THE WRITE
# ---------------------------------------------------------------------------
@mcp.tool(
    annotations=ToolAnnotations(
        title="Record a stock movement",
        readOnlyHint=False,
        destructiveHint=True,
        idempotentHint=True,   # true only because of the derived Idempotency-Key below
        openWorldHint=False,
    )
)
async def record_movement(
    sku: str,
    warehouse_code: str,
    movement_type: str,
    quantity: int,
    ctx: Context,
    reference_type: str | None = None,
    notes: str | None = None,
) -> dict[str, Any]:
    """Record a physical stock movement. THIS WRITES TO THE DATABASE.

    Args:
        sku: Product code, for example ``ELEC-LAP-001``.
        warehouse_code: Warehouse code matching ``WH-[A-Z]{2,4}``, for example ``WH-EAST``.
        movement_type: One of IN, OUT, TRANSFER_IN, TRANSFER_OUT, ADJUSTMENT, RETURN.
        quantity: Positive whole number of units. The direction comes from
            movement_type, so use 25 with OUT rather than -25 with IN.
        reference_type: Optional free-text origin, such as ``PURCHASE_ORDER``.
        notes: Optional human-readable note stored with the movement.

    The server derives an Idempotency-Key from these arguments, so calling this
    twice with identical arguments in one session records ONE movement and the
    second response has ``replayed: true``. That is a safety net, not a licence -
    ask the user before recording a movement you were not explicitly asked for.
    """
    span_attrs = {
        "inventory.sku": sku,
        "inventory.warehouse": warehouse_code,
        "inventory.movement_type": movement_type,
        "inventory.quantity": quantity,
    }
    if movement_type not in MOVEMENT_TYPES:
        # Caught here rather than at the facade so the model gets the valid set
        # back instead of a bare 400.
        raise InventoryError(
            f"movement_type must be one of {', '.join(MOVEMENT_TYPES)}; got {movement_type!r}."
        )
    if quantity <= 0:
        raise InventoryError(
            f"quantity must be a positive number of units; got {quantity}. "
            f"Use movement_type to express direction, not a negative quantity."
        )

    _check_sku(sku)
    _check_warehouse(warehouse_code)

    with tool_span("record_movement", **span_attrs) as span:
        ctxt = _ctx(ctx)
        key = movement_idempotency_key(
            ctxt.session_id, sku, warehouse_code, movement_type, quantity
        )
        # The derived key on the span, so a duplicate write is diagnosable from
        # the trace alone rather than by reasoning about what the model did.
        span.set_attribute("inventory.idempotency_key", key)
        await ctx.info(f"Recording {movement_type} {quantity} x {sku} at {warehouse_code}")

        result = await ctxt.client.record_movement(
            sku=sku,
            warehouse_code=warehouse_code,
            movement_type=movement_type,
            quantity=quantity,
            reference_type=reference_type,
            notes=notes,
            idempotency_key=key,
        )
        span.set_attribute("inventory.replayed", bool(result.get("replayed")))
        return result


# ---------------------------------------------------------------------------
# RESOURCES - context a model can read without spending a tool call
# ---------------------------------------------------------------------------
@mcp.resource(
    "inventory://contract/openapi",
    name="REST contract (OpenAPI)",
    description="The hand-written OpenAPI 3 spec these tools wrap.",
    mime_type="application/yaml",
)
def openapi_contract() -> str:
    """Serve the Phase 2 contract itself as an MCP resource.

    The same document governs the REST consumer and the LLM consumer, which is
    the whole argument of this phase: MCP did not replace the contract, it
    republished it in a form a model can read.
    """
    if not OPENAPI_PATH.exists():
        return f"# openapi.yaml not found at {OPENAPI_PATH}\n"
    return OPENAPI_PATH.read_text(encoding="utf-8")


@mcp.resource(
    "inventory://contract/vocabulary",
    name="Domain vocabulary",
    description="Valid SKU/warehouse patterns and movement types, from the Phase 1 XSD.",
    mime_type="application/json",
)
def vocabulary() -> dict[str, Any]:
    """The closed value sets, so a model can check itself before calling."""
    return {
        "skuPattern": SKU_PATTERN,
        "skuExample": "ELEC-LAP-001",
        "warehousePattern": WAREHOUSE_PATTERN,
        "warehouses": list(KNOWN_WAREHOUSES),
        "warehouseNote": (
            "The pattern admits far more codes than exist - WH-NYC matches it and is "
            "not a warehouse. Pick from `warehouses`; anything else returns 404."
        ),
        "movementTypes": list(MOVEMENT_TYPES),
        "source": "phase1-soap-service XSD; mirrored by Bean Validation in phase2-rest-facade",
    }


@mcp.resource(
    "inventory://gateway/policy",
    name="Gateway policy",
    description="Auth, rate limits and tracing applied to every call these tools make.",
    mime_type="application/json",
)
def gateway_policy() -> dict[str, Any]:
    """What the platform enforces regardless of what the model intends."""
    return {
        "gateway": settings.base_url,
        "authentication": f"{settings.api_key_header} header, enforced by Kong key-auth",
        "rateLimit": "20 requests/minute per consumer, counted in Redis",
        "tracing": "X-Correlation-ID generated by this client and propagated to SOAP and Postgres",
        "writeProtection": "Idempotency-Key derived from the movement's arguments",
        "note": "These are enforced by infrastructure, not by the model's good behaviour.",
    }


# ---------------------------------------------------------------------------
# PROMPTS - the investigation, packaged
# ---------------------------------------------------------------------------
@mcp.prompt(
    name="investigate_low_stock",
    title="Investigate why a SKU is low",
    description="Guided root-cause walkthrough for a single SKU.",
)
def investigate_low_stock(sku: str) -> str:
    """A repeatable investigation, so the answer does not depend on phrasing."""
    return (
        f"Investigate why {sku} is low on stock. Work in this order and show your reasoning:\n"
        f"1. get_product({sku}) - what is its reorderPoint and reorderQuantity?\n"
        f"2. get_stock({sku}) - which warehouses are below that point, and by how much?\n"
        f"3. daily_movement_totals() - what recent movements explain the drop? Look for an "
        f"unusually large OUT, or a steady drain with no matching IN.\n"
        f"4. platform_status() - if the outbox has a backlog, say so: the movement data may be "
        f"incomplete and your conclusion is provisional.\n\n"
        f"Finish with a recommendation of how many units to order and to which warehouse. "
        f"Do NOT record any movement - recommend, and let a human decide."
    )


@mcp.prompt(
    name="restock_plan",
    title="Draft a restock plan",
    description="Turn the low-stock list into a prioritised ordering plan.",
)
def restock_plan(warehouse: str = "") -> str:
    scope = f"warehouse {warehouse}" if warehouse else "every warehouse"
    return (
        f"Draft a restock plan for {scope}.\n"
        f"1. list_low_stock({warehouse!r}) for the full picture in one call.\n"
        f"2. For the five worst shortfalls, get_product to read reorderQuantity.\n"
        f"3. Rank by shortfall against reorder point, and by unit cost where it breaks a tie.\n"
        f"Return a table of SKU, warehouse, on hand, reorder point, suggested order quantity, "
        f"and estimated cost. Recommend only - recording movements is a human decision."
    )
