"""
The MCP surface: what a model actually sees, and what it is stopped from doing.

Everything runs over an in-memory client/server pair with the HTTP layer
mocked, so the suite needs no gateway, no database and no network.
"""

from __future__ import annotations

import json

import httpx
import respx
from mcp.shared.memory import create_connected_server_and_client_session

from inventory_mcp.config import settings
from inventory_mcp.server import KNOWN_WAREHOUSES, MOVEMENT_TYPES, mcp

PRODUCT_URL = f"{settings.rest_root}/products/ELEC-LAP-001"
MOVEMENTS_URL = f"{settings.rest_root}/movements"


def _text(result) -> str:
    return "\n".join(b.text for b in result.content if getattr(b, "type", None) == "text")


class TestToolContract:
    """The tool schema IS the model's copy of the contract."""

    async def test_write_tool_is_flagged_destructive(self):
        tools = await mcp.list_tools()
        write = next(t for t in tools if t.name == "record_movement")
        assert write.annotations.readOnlyHint is False
        assert write.annotations.destructiveHint is True
        # Idempotent only because the server derives the key - worth asserting
        # so the annotation cannot drift away from the behaviour.
        assert write.annotations.idempotentHint is True

    async def test_reads_are_flagged_read_only(self):
        tools = {t.name: t for t in await mcp.list_tools()}
        for name in ("get_product", "get_stock", "list_low_stock", "daily_movement_totals"):
            assert tools[name].annotations.readOnlyHint is True, name

    async def test_context_is_not_exposed_as_a_parameter(self):
        """FastMCP injects Context; a model must never be asked to supply it."""
        tools = {t.name: t for t in await mcp.list_tools()}
        for tool in tools.values():
            assert "ctx" not in tool.inputSchema.get("properties", {}), tool.name

    async def test_resources_and_prompts_are_published(self):
        uris = {str(r.uri) for r in await mcp.list_resources()}
        assert "inventory://contract/vocabulary" in uris
        assert "inventory://gateway/policy" in uris
        names = {p.name for p in await mcp.list_prompts()}
        assert {"investigate_low_stock", "restock_plan"} <= names


class TestGuardsAgainstAModel:
    """A model improvises. These are the arguments it improvises wrongly."""

    @respx.mock
    async def test_unknown_movement_type_is_rejected_with_the_valid_set(self):
        route = respx.post(MOVEMENTS_URL).mock(return_value=httpx.Response(201, json={}))
        async with create_connected_server_and_client_session(mcp) as session:
            result = await session.call_tool(
                "record_movement",
                {"sku": "ELEC-LAP-001", "warehouse_code": "WH-EAST",
                 "movement_type": "SHIPPED", "quantity": 5},
            )
        assert result.isError
        # The model needs the closed set back, not a bare 400.
        assert all(t in _text(result) for t in MOVEMENT_TYPES)
        assert not route.called, "an invalid movement must never reach the facade"

    @respx.mock
    async def test_negative_quantity_is_rejected_before_the_write(self):
        route = respx.post(MOVEMENTS_URL).mock(return_value=httpx.Response(201, json={}))
        async with create_connected_server_and_client_session(mcp) as session:
            result = await session.call_tool(
                "record_movement",
                {"sku": "ELEC-LAP-001", "warehouse_code": "WH-EAST",
                 "movement_type": "OUT", "quantity": -25},
            )
        assert result.isError
        assert "positive" in _text(result).lower()
        assert not route.called


class TestArgumentValidation:
    """The facade does not validate every path, so the server does it first."""

    @respx.mock
    async def test_a_trailing_junk_sku_is_rejected(self):
        """Full-match semantics. re.match would accept this and 502 upstream."""
        route = respx.get(f"{settings.rest_root}/stock/ELEC-LAP-001-JUNK").mock(
            return_value=httpx.Response(200, json=[])
        )
        async with create_connected_server_and_client_session(mcp) as session:
            result = await session.call_tool("get_stock", {"sku": "ELEC-LAP-001-JUNK"})
        assert result.isError
        assert not route.called, "an invalid SKU reached the facade and would 502"

    @respx.mock
    async def test_a_malformed_warehouse_names_the_real_ones(self):
        route = respx.get(f"{settings.rest_root}/stock/ELEC-LAP-001").mock(
            return_value=httpx.Response(200, json=[])
        )
        async with create_connected_server_and_client_session(mcp) as session:
            result = await session.call_tool(
                "get_stock", {"sku": "ELEC-LAP-001", "warehouse": "New York"}
            )
        assert result.isError
        assert all(w in _text(result) for w in KNOWN_WAREHOUSES)
        assert not route.called

    @respx.mock
    async def test_a_wellformed_but_unknown_warehouse_is_NOT_blocked(self):
        """WH-NYC matches the pattern, so the server must not invent an allowlist.

        The database owns which warehouses exist; hardcoding the set here would
        go stale the day one is added. The vocabulary resource steers the model,
        and a genuinely unknown code comes back as a 404 the model can read.
        """
        route = respx.get(f"{settings.rest_root}/stock/ELEC-LAP-001").mock(
            return_value=httpx.Response(404)
        )
        async with create_connected_server_and_client_session(mcp) as session:
            result = await session.call_tool(
                "get_stock", {"sku": "ELEC-LAP-001", "warehouse": "WH-NYC"}
            )
        assert route.called, "the server second-guessed the database"
        assert result.isError and "No such record" in _text(result)


class TestIdempotencyEndToEnd:
    @respx.mock
    async def test_repeated_identical_calls_reuse_one_key(self):
        """The behaviour that makes a write tool safe for a model to hold.

        Two identical calls in one session must carry the SAME key, so the
        facade replays the first result instead of moving stock twice.
        """
        route = respx.post(MOVEMENTS_URL).mock(
            return_value=httpx.Response(201, json={"movementId": 7, "replayed": False})
        )
        args = {"sku": "ELEC-LAP-001", "warehouse_code": "WH-EAST",
                "movement_type": "OUT", "quantity": 25}

        async with create_connected_server_and_client_session(mcp) as session:
            await session.call_tool("record_movement", args)
            await session.call_tool("record_movement", args)

        assert route.call_count == 2
        keys = {c.request.headers["Idempotency-Key"] for c in route.calls}
        assert len(keys) == 1, "a retry generated a new key and would double-count stock"


class TestResources:
    async def test_vocabulary_matches_the_server_constants(self):
        async with create_connected_server_and_client_session(mcp) as session:
            result = await session.read_resource("inventory://contract/vocabulary")
        payload = json.loads(result.contents[0].text)
        assert payload["movementTypes"] == list(MOVEMENT_TYPES)
        assert payload["skuExample"] == "ELEC-LAP-001"
        # A pattern is not a value set: publish the warehouses that exist.
        assert payload["warehouses"] == list(KNOWN_WAREHOUSES)
