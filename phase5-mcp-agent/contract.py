"""
Print the contract this server publishes to a model.

    ./.venv/bin/python contract.py

Everything here is derived from the tool functions themselves - the type hints
become the JSON Schema and the docstrings become the descriptions - so this is
not documentation that can drift from the code. It IS the code, rendered.
"""

from __future__ import annotations

import asyncio

from inventory_mcp.server import mcp

FLAGS = (("readOnly", "readOnlyHint"), ("DESTRUCTIVE", "destructiveHint"),
         ("idempotent", "idempotentHint"))


async def main() -> None:
    tools = await mcp.list_tools()
    print(f"{'TOOL':24} {'ANNOTATIONS':34} PARAMETERS")
    print("-" * 92)
    for tool in tools:
        annotations = tool.annotations
        flags = ",".join(
            label for label, attr in FLAGS if getattr(annotations, attr, None)
        )
        params = ", ".join(tool.inputSchema.get("properties", {}))
        print(f"{tool.name:24} {flags:34} {params[:32]}")

    print()
    for resource in await mcp.list_resources():
        print(f"resource  {resource.uri}")
    for prompt in await mcp.list_prompts():
        args = ", ".join(a.name for a in (prompt.arguments or []))
        print(f"prompt    {prompt.name}({args})")


if __name__ == "__main__":
    asyncio.run(main())
