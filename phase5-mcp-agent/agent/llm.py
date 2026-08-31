"""
Model selection, including a scripted model that needs no credentials.

Azure OpenAI is the headline provider, but the repo's rule is that a reviewer
runs the project in five minutes. Requiring an Azure subscription before
anything happens would break that, so `offline` is a real, deterministic model
that drives the REAL MCP tools against the REAL platform. What you lose without
credentials is the language, not the plumbing.
"""

from __future__ import annotations

import os
import re
from typing import Any

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field

from inventory_mcp.config import settings

SKU_RE = re.compile(r"\b[A-Z]{3,4}-[A-Z0-9]{3,5}-?[0-9]{0,5}\b")


def _text_of(message: ToolMessage) -> str:
    """Flatten a tool result to plain text.

    Tool content is a list of typed blocks, not a string. Printing the list
    repr leaks `{'type': 'text', 'id': 'lc_...'}` noise into the answer.
    """
    content = message.content
    if isinstance(content, str):
        return content
    parts = []
    for block in content if isinstance(content, list) else []:
        if isinstance(block, dict) and block.get("type") == "text":
            parts.append(str(block.get("text", "")))
        elif isinstance(block, str):
            parts.append(block)
    return "\n".join(parts)


class OfflineChatModel(BaseChatModel):
    """A scripted stand-in that emits real tool calls in a fixed order.

    Not a mock of the tools - the tools are genuine MCP calls hitting the
    gateway. This only replaces the part that costs money, so the transport,
    the tool schemas, the approval interrupt and the idempotency key are all
    exercised exactly as they are with a real model.
    """

    tool_names: list[str] = Field(default_factory=list)

    @property
    def _llm_type(self) -> str:
        return "offline-scripted"

    def bind_tools(self, tools: list[Any], **kwargs: Any) -> OfflineChatModel:
        names = []
        for tool in tools:
            name = getattr(tool, "name", None) or getattr(tool, "__name__", None)
            if name:
                names.append(name)
        return self.__class__(tool_names=names)

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        question = next(
            (m.content for m in messages if m.type == "human"), ""
        )
        sku_match = SKU_RE.search(str(question))
        sku = sku_match.group(0) if sku_match else None

        # Step counter: how many tool round-trips have already come back.
        completed = sum(1 for m in messages if isinstance(m, ToolMessage))

        plan: list[tuple[str, dict[str, Any]]] = []
        if sku and "get_product" in self.tool_names:
            plan.append(("get_product", {"sku": sku}))
        if sku and "get_stock" in self.tool_names:
            plan.append(("get_stock", {"sku": sku}))
        if not sku and "list_low_stock" in self.tool_names:
            plan.append(("list_low_stock", {}))

        if completed < len(plan):
            name, args = plan[completed]
            message = AIMessage(
                content="",
                tool_calls=[{"name": name, "args": args, "id": f"offline-{completed}"}],
            )
        else:
            findings = [_text_of(m) for m in messages if isinstance(m, ToolMessage)]
            body = "\n\n".join(f for f in findings if f) or "No tools were available to call."
            message = AIMessage(
                content=(
                    "[offline model - no LLM credentials configured, so this is a "
                    "scripted summary of real tool output]\n\n" + body
                )
            )

        return ChatResult(generations=[ChatGeneration(message=message)])


def build_model(provider: str | None = None) -> BaseChatModel:
    """Return a chat model for the configured provider.

    `auto` (the default) picks whichever provider actually has credentials in
    the environment and falls back to `offline`, so the same command works on a
    laptop with no keys and in a deployment with Azure configured.
    """
    provider = (provider or settings.resolved_provider()).lower()

    if provider == "azure":
        from langchain_openai import AzureChatOpenAI

        # Deployment name != model name. AZURE_OPENAI_DEPLOYMENT is the name you
        # gave the deployment in the Azure portal; the model behind it is a
        # property of that deployment, not of this call. Mixing them up is the
        # single most common Azure OpenAI error.
        return AzureChatOpenAI(
            azure_deployment=os.environ.get("AZURE_OPENAI_DEPLOYMENT", "gpt-4o"),
            api_version=os.environ.get("OPENAI_API_VERSION", "2024-10-21"),
            temperature=0,
        )

    if provider == "openai":
        from langchain_openai import ChatOpenAI

        return ChatOpenAI(model=os.environ.get("OPENAI_MODEL", "gpt-4o"), temperature=0)

    if provider == "anthropic":
        from langchain_anthropic import ChatAnthropic  # optional extra

        return ChatAnthropic(
            model=os.environ.get("ANTHROPIC_MODEL", "claude-sonnet-4-5"), temperature=0
        )

    if provider == "offline":
        return OfflineChatModel()

    raise ValueError(
        f"Unknown MCP_LLM_PROVIDER {provider!r}. Use azure, openai, anthropic, offline or auto."
    )
