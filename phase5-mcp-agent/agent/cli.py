"""
Command line for the agent.

    inventory-agent "what needs restocking in WH-EAST?"
    inventory-agent --http "..."          # talk to a server already running on :8084
    inventory-agent --provider azure "..."

Prints the tool-call trace as it goes, because the trace is the interesting
part: you can watch the model decide to list low stock, pick a SKU and read its
reorder point before it writes a sentence.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Any

from langchain_core.messages import HumanMessage
from langgraph.types import Command

from agent.graph import build_agent
from inventory_mcp.config import settings

THREAD = {"configurable": {"thread_id": "cli"}}


def _flatten(content: Any) -> str:
    """Tool results arrive as typed content blocks, not strings."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = [
            str(b.get("text", "")) if isinstance(b, dict) and b.get("type") == "text"
            else b if isinstance(b, str) else ""
            for b in content
        ]
        return "\n".join(p for p in parts if p)
    return str(content)


def _short(value: Any, limit: int = 220) -> str:
    text = value if isinstance(value, str) else json.dumps(value, default=str)
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit] + " ..."


def _render(chunk: dict[str, Any]) -> None:
    """Print tool calls and answers as the graph emits them."""
    for update in chunk.values():
        if not isinstance(update, dict):
            continue
        for message in update.get("messages", []):
            calls = getattr(message, "tool_calls", None)
            if calls:
                for call in calls:
                    print(f"  → {call['name']}({_short(call['args'], 120)})", file=sys.stderr)
            elif message.type == "tool":
                print(f"  ← {message.name}: {_short(_flatten(message.content))}", file=sys.stderr)
            elif message.type == "ai" and message.content:
                print(f"\n{_flatten(message.content)}\n")


def _ask_human(request: dict[str, Any]) -> dict[str, Any]:
    """The approval gate. The graph stays paused, checkpointed, while this blocks.

    Returns the middleware's decision protocol: one decision per action request.
    """
    decisions: list[dict[str, Any]] = []
    for action in request.get("action_requests", []):
        print("\n" + "=" * 72, file=sys.stderr)
        print(action.get("description") or action.get("name"), file=sys.stderr)
        print("=" * 72, file=sys.stderr)
        answer = input("Approve this write? [y/N] ").strip().lower()
        if answer in ("y", "yes"):
            decisions.append({"type": "approve"})
        else:
            decisions.append(
                {"type": "reject", "message": "The operator declined. Do not retry; "
                                              "explain to the user that it was not recorded."}
            )
    return {"decisions": decisions}


async def run(question: str, *, transport: str, url: str | None, provider: str | None) -> int:
    bundle = await build_agent(transport=transport, url=url, provider=provider)
    print(
        f"model: {bundle.provider}   tools: {len(bundle.tools)}   gateway: {settings.base_url}",
        file=sys.stderr,
    )

    payload: Any = {"messages": [HumanMessage(question)]}
    while True:
        interrupted = False
        async for chunk in bundle.graph.astream(payload, THREAD):
            if "__interrupt__" in chunk:
                payload = Command(resume=_ask_human(chunk["__interrupt__"][0].value))
                interrupted = True
                break
            _render(chunk)
        if not interrupted:
            return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="inventory-agent", description=__doc__)
    parser.add_argument("question", nargs="*", help="what to ask")
    parser.add_argument("--http", action="store_true", help="connect to a running server on :8084")
    parser.add_argument("--url", default=None, help="explicit streamable-HTTP URL")
    parser.add_argument(
        "--provider", default=None, help="azure | openai | anthropic | offline (default: auto)"
    )
    args = parser.parse_args(argv)

    question = " ".join(args.question).strip()
    if not question:
        parser.error('ask a question, e.g. "what needs restocking?"')

    return asyncio.run(
        run(
            question,
            transport="streamable_http" if (args.http or args.url) else "stdio",
            url=args.url,
            provider=args.provider,
        )
    )


if __name__ == "__main__":
    raise SystemExit(main())
