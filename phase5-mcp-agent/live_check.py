"""
Live assertions that need a running platform, called by verify.sh section 4.

Kept out of tests/ on purpose: the pytest suite must stay offline, and these
only mean anything when the gateway and database are actually up.
"""

import asyncio
import sys

from mcp.shared.memory import create_connected_server_and_client_session

from inventory_mcp.server import KNOWN_WAREHOUSES, mcp

GREEN, RED, OFF = "\033[32m", "\033[31m", "\033[0m"


def report(ok: bool, message: str) -> bool:
    print(f"  {GREEN}PASS{OFF} {message}" if ok else f"  {RED}FAIL{OFF} {message}")
    return ok


def body_of(result) -> str:
    return "".join(b.text for b in result.content if getattr(b, "type", None) == "text")


async def main() -> int:
    ok = True
    async with create_connected_server_and_client_session(mcp) as session:
        # The empty list the facade really returns must not reach the model.
        result = await session.call_tool(
            "get_stock", {"sku": "ELEC-LAP-001", "warehouse": "WH-NYC"}
        )
        text = body_of(result)
        ok &= report(
            result.isError and all(w in text for w in KNOWN_WAREHOUSES),
            "an unknown warehouse becomes an error naming the real ones",
        )

        # A real warehouse must still come back normally.
        result = await session.call_tool(
            "get_stock", {"sku": "ELEC-LAP-001", "warehouse": "WH-EAST"}
        )
        ok &= report(not result.isError, "a real warehouse still returns stock")

        # Malformed SKUs are caught here, so the 502 never happens.
        result = await session.call_tool("get_stock", {"sku": "not-a-sku"})
        ok &= report(
            result.isError and "not a valid SKU" in body_of(result),
            "a malformed SKU is rejected locally, never reaching the 502",
        )
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
