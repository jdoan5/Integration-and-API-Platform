"""
The agent: the approval gate, the call budget, and the scripted offline model.

No MCP subprocess and no network - the middleware is exercised inside a real
compiled agent with a stub tool standing in for the write.
"""

from __future__ import annotations

from typing import Any

import pytest
from langchain.agents import create_agent
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import StructuredTool
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.types import Command

from agent.graph import GUARDED_TOOLS, TOOL_CALL_BUDGET, build_middleware
from agent.llm import OfflineChatModel

CALLS: list[dict[str, Any]] = []


async def _write(sku: str, quantity: int) -> str:
    CALLS.append({"sku": sku, "quantity": quantity})
    return f"recorded {quantity} x {sku}"


class _CallsTheWriteOnce(BaseChatModel):
    """Calls the guarded tool, then stops."""

    @property
    def _llm_type(self) -> str:
        return "test-script"

    def bind_tools(self, tools: list[Any], **kwargs: Any) -> _CallsTheWriteOnce:
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs) -> ChatResult:
        if any(isinstance(m, ToolMessage) for m in messages):
            return ChatResult(generations=[ChatGeneration(message=AIMessage("done"))])
        return ChatResult(generations=[ChatGeneration(message=AIMessage(
            content="",
            tool_calls=[{"name": "record_movement",
                         "args": {"sku": "ELEC-LAP-001", "quantity": 25},
                         "id": "call-1"}],
        ))])


def _graph(require_approval: bool = True):
    tool = StructuredTool.from_function(
        coroutine=_write, name="record_movement", description="Write stock."
    )
    return create_agent(
        _CallsTheWriteOnce(),
        [tool],
        middleware=build_middleware(require_approval),
        checkpointer=InMemorySaver(),
    )


@pytest.fixture(autouse=True)
def _clear():
    CALLS.clear()
    yield
    CALLS.clear()


class TestApprovalGate:
    async def test_the_write_pauses_instead_of_running(self):
        result = await _graph().ainvoke(
            {"messages": [HumanMessage("record it")]}, {"configurable": {"thread_id": "t1"}}
        )

        assert "__interrupt__" in result, "the graph did not pause before writing"
        request = result["__interrupt__"][0].value
        action = request["action_requests"][0]
        assert action["name"] == "record_movement"
        assert action["args"] == {"sku": "ELEC-LAP-001", "quantity": 25}
        # The operator must see every argument, not a summary.
        assert "ELEC-LAP-001" in action["description"]
        assert CALLS == [], "the write ran before anyone approved it"

    async def test_rejecting_prevents_the_write(self):
        config = {"configurable": {"thread_id": "t2"}}
        graph = _graph()
        await graph.ainvoke({"messages": [HumanMessage("record it")]}, config)

        result = await graph.ainvoke(
            Command(resume={"decisions": [{"type": "reject", "message": "not authorised"}]}),
            config,
        )
        assert CALLS == [], "a rejected write still hit the database"
        # The refusal comes back as a tool result so the model can explain it.
        tool_messages = [m for m in result["messages"] if isinstance(m, ToolMessage)]
        assert "not authorised" in str(tool_messages[-1].content)

    async def test_approving_lets_the_write_through(self):
        config = {"configurable": {"thread_id": "t3"}}
        graph = _graph()
        await graph.ainvoke({"messages": [HumanMessage("record it")]}, config)
        await graph.ainvoke(Command(resume={"decisions": [{"type": "approve"}]}), config)

        assert CALLS == [{"sku": "ELEC-LAP-001", "quantity": 25}]

    async def test_without_approval_the_write_runs_straight_through(self):
        """The gate is opt-out, so the test proves it is the gate doing the work."""
        await _graph(require_approval=False).ainvoke(
            {"messages": [HumanMessage("record it")]}, {"configurable": {"thread_id": "t4"}}
        )
        assert CALLS == [{"sku": "ELEC-LAP-001", "quantity": 25}]

    def test_only_the_write_is_guarded(self):
        """Gating every tool would stop the agent before each read."""
        assert GUARDED_TOOLS == {"record_movement"}


class TestCallBudget:
    def test_budget_stays_under_the_gateway_rate_limit(self):
        """Kong allows 20/minute; the agent must give up before Kong says 429."""
        assert TOOL_CALL_BUDGET < 20

    def test_budget_middleware_is_always_installed(self):
        """Even with approval disabled, the call cap stays on."""
        names = [type(m).__name__ for m in build_middleware(require_approval=False)]
        assert "ToolCallLimitMiddleware" in names


class TestOfflineModel:
    def test_it_extracts_the_sku_and_calls_a_real_tool(self):
        model = OfflineChatModel().bind_tools(
            [type("T", (), {"name": "get_product"})(), type("T", (), {"name": "get_stock"})()]
        )
        response = model.invoke([HumanMessage("why is ELEC-LAP-001 low?")])
        assert response.tool_calls[0]["name"] == "get_product"
        assert response.tool_calls[0]["args"] == {"sku": "ELEC-LAP-001"}

    def test_no_sku_falls_back_to_the_low_stock_sweep(self):
        model = OfflineChatModel().bind_tools([type("T", (), {"name": "list_low_stock"})()])
        response = model.invoke([HumanMessage("what needs restocking?")])
        assert response.tool_calls[0]["name"] == "list_low_stock"

    def test_it_stops_after_the_script_and_summarises(self):
        model = OfflineChatModel().bind_tools([type("T", (), {"name": "get_product"})()])
        response = model.invoke([
            HumanMessage("why is ELEC-LAP-001 low?"),
            AIMessage(content="", tool_calls=[
                {"name": "get_product", "args": {"sku": "ELEC-LAP-001"}, "id": "x"}]),
            ToolMessage(content="reorderPoint 40", tool_call_id="x", name="get_product"),
        ])
        assert not response.tool_calls
        assert "reorderPoint 40" in response.content
