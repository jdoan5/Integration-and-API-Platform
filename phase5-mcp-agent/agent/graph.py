"""
The LangGraph agent that consumes the MCP server.

The *loop* is not what a framework buys you - Python Project 6 hand-rolls one in
forty lines and it works fine. What it buys you is everything around the loop:
checkpointed state you can pause, a place to put a human in the middle of a tool
call, a resume that continues rather than restarts, and a call budget.

Which happens to be the exact list of things you need before you let a model
near a write endpoint and a rate-limited gateway.
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from typing import Any

from langchain.agents import create_agent
from langchain.agents.middleware import (
    HumanInTheLoopMiddleware,
    InterruptOnConfig,
    ToolCallLimitMiddleware,
)
from langchain_core.tools import BaseTool
from langchain_mcp_adapters.client import MultiServerMCPClient
from langgraph.checkpoint.memory import InMemorySaver

from agent.llm import build_model

# Tools that change the world. Everything else runs unattended - gating reads
# too would stop the agent before every lookup and make it unusable.
GUARDED_TOOLS = {"record_movement"}

# Kong allows this consumer 20 requests/minute (phase3-gateway/kong.yml). A model
# that decides to check forty SKUs individually will hit that and start seeing
# 429s halfway through an answer. Capping tool calls below the gateway's limit
# turns an infrastructure failure into a predictable agent behaviour.
TOOL_CALL_BUDGET = 15

SYSTEM_PROMPT = """You are an inventory operations analyst for a warehouse platform.

You answer questions by calling tools, not by guessing. Rules:
- SKUs look like ELEC-LAP-001, warehouses like WH-EAST, and movement types are a
  closed set: IN, OUT, TRANSFER_IN, TRANSFER_OUT, ADJUSTMENT, RETURN.
- For "what needs restocking" call list_low_stock ONCE rather than checking SKUs
  one at a time - the gateway rate-limits you to 20 calls per minute.
- Recording a movement writes to a real database. Only do it when the user has
  clearly asked for it, and never twice for the same physical event.
- When a tool returns an error, read it: some errors mean "fix your arguments"
  and some mean "the platform is misconfigured". Do not retry the second kind.
- Cite the numbers you actually saw. If the event pipeline is behind, say your
  answer may be incomplete."""


def _describe_write(tool_call: Any, state: Any, runtime: Any) -> str:
    """What the operator sees before approving. Arguments in full, no summary.

    An approval prompt that hides an argument is worse than no prompt: it
    trains the operator to click yes.
    """
    args = json.dumps(tool_call["args"], indent=2, sort_keys=True)
    return (
        f"The agent wants to WRITE to the inventory database.\n\n"
        f"Tool: {tool_call['name']}\n{args}\n\n"
        f"This records a real stock movement and publishes a domain event."
    )


def mcp_connection(transport: str = "stdio", url: str | None = None) -> dict[str, Any]:
    """Where to find the MCP server.

    stdio spawns the server as a child process, which is exactly how Claude
    Desktop and Claude Code launch it - so the agent exercises the same path a
    real MCP client uses, rather than a special one built for itself.
    """
    if transport == "stdio":
        return {
            "inventory": {
                "command": sys.executable,
                "args": ["-m", "inventory_mcp"],
                "transport": "stdio",
            }
        }
    return {"inventory": {"url": url or "http://127.0.0.1:8084/mcp", "transport": "streamable_http"}}


def build_middleware(require_approval: bool = True) -> list[Any]:
    """Approval on writes, and a call budget that respects the gateway."""
    middleware: list[Any] = [
        ToolCallLimitMiddleware(run_limit=TOOL_CALL_BUDGET, exit_behavior="continue")
    ]
    if require_approval:
        middleware.append(
            HumanInTheLoopMiddleware(
                interrupt_on={
                    name: InterruptOnConfig(
                        allowed_decisions=["approve", "edit", "reject"],
                        description=_describe_write,
                    )
                    for name in GUARDED_TOOLS
                },
                description_prefix="Inventory write requires approval",
            )
        )
    return middleware


@dataclass
class AgentBundle:
    graph: Any
    tools: list[BaseTool]
    provider: str


async def build_agent(
    *,
    transport: str = "stdio",
    url: str | None = None,
    provider: str | None = None,
    require_approval: bool = True,
) -> AgentBundle:
    """Load the MCP tools and compile the agent around them."""
    client = MultiServerMCPClient(mcp_connection(transport, url))
    tools = await client.get_tools()

    model = build_model(provider)
    graph = create_agent(
        model,
        tools,
        system_prompt=SYSTEM_PROMPT,
        middleware=build_middleware(require_approval),
        # Required, not optional: an interrupt has to persist the paused state
        # somewhere or there is nothing to resume into.
        checkpointer=InMemorySaver(),
    )
    return AgentBundle(graph=graph, tools=tools, provider=type(model).__name__)
