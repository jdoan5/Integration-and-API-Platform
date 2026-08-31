"""
Run the MCP server.

    python -m inventory_mcp                      # stdio  - for Claude Code / Claude Desktop
    python -m inventory_mcp --http               # streamable HTTP on :8084 - for the agent

stdio is the default because that is how a desktop MCP client launches a
server: it spawns the process and talks over the pipes. Which means stdout
belongs to the protocol - a stray print() corrupts the stream and the client
reports a cryptic parse error. Log to stderr or not at all.
"""

from __future__ import annotations

import argparse
import sys

from .config import settings
from .server import mcp


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="inventory-mcp", description=__doc__)
    parser.add_argument(
        "--http",
        action="store_true",
        help=f"serve streamable HTTP on {settings.host}:{settings.port} instead of stdio",
    )
    args = parser.parse_args(argv)

    if args.http:
        # Note for anyone reading the ports: FastMCP defaults to 8000, which is
        # Kong's proxy port in this repo. config.py moves it to 8084.
        print(
            f"inventory-mcp listening on http://{settings.host}:{settings.port}/mcp",
            file=sys.stderr,
        )
        mcp.run(transport="streamable-http")
    else:
        mcp.run(transport="stdio")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
