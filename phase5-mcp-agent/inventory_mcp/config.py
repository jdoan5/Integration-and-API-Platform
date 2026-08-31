"""
Configuration for the MCP server and the agent.

Same principle as every other phase: every value has a working local default,
so `python -m inventory_mcp` runs with no .env at all. The environment is for
pointing this at real infrastructure, not for making it start.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

# The gateway, not the service. Phase 3 put auth, rate limiting and the
# correlation ID in Kong; calling 8082 directly would skip all three and the
# agent would be the one consumer that bypasses the platform's own policy.
DEFAULT_BASE_URL = "http://localhost:8000"
DEFAULT_API_KEY = "local-demo-key-internal"

# 8084 is deliberate. FastMCP defaults to 8000 - which is Kong's proxy port in
# this repo, so the default would bind on top of the gateway.
DEFAULT_MCP_PORT = 8084


def _env(name: str, default: str) -> str:
    """os.environ.get, but an empty string counts as unset.

    Matters because the repo's .env.example ships keys with blank values, and
    `DB_USER=` should mean "use the default", not "use the empty string".
    """
    value = os.environ.get(name)
    return value if value else default


@dataclass(frozen=True)
class Settings:
    """Everything the server and agent read from the environment."""

    base_url: str = field(default_factory=lambda: _env("MCP_BASE_URL", DEFAULT_BASE_URL))
    api_key: str = field(default_factory=lambda: _env("MCP_API_KEY", DEFAULT_API_KEY))
    api_key_header: str = "apikey"

    # Phase 4's status app is not behind Kong - it is an internal operator view.
    events_url: str = field(default_factory=lambda: _env("MCP_EVENTS_URL", "http://localhost:8083"))

    client_id: str = field(default_factory=lambda: _env("MCP_CLIENT_ID", "mcp-agent"))
    timeout_seconds: float = field(default_factory=lambda: float(_env("MCP_TIMEOUT", "10")))

    host: str = field(default_factory=lambda: _env("MCP_HOST", "127.0.0.1"))
    port: int = field(default_factory=lambda: int(_env("MCP_PORT", str(DEFAULT_MCP_PORT))))

    # azure | openai | anthropic | offline.  "offline" is a scripted model that
    # needs no credentials and still drives the real MCP tools.
    llm_provider: str = field(default_factory=lambda: _env("MCP_LLM_PROVIDER", "auto").lower())

    # Tracing. Same OTLP endpoint the Java services use, so the spans land in
    # the same Jaeger and join the same trace.
    otlp_endpoint: str = field(
        default_factory=lambda: _env("OTLP_ENDPOINT", "http://localhost:4318/v1/traces"))
    tracing_enabled: bool = field(
        default_factory=lambda: _env("MCP_TRACING", "1") not in ("0", "false", "no"))

    @property
    def rest_root(self) -> str:
        return f"{self.base_url.rstrip('/')}/api/v1"

    def resolved_provider(self) -> str:
        """`auto` picks whichever provider actually has credentials."""
        if self.llm_provider != "auto":
            return self.llm_provider
        if os.environ.get("AZURE_OPENAI_API_KEY") and os.environ.get("AZURE_OPENAI_ENDPOINT"):
            return "azure"
        if os.environ.get("OPENAI_API_KEY"):
            return "openai"
        if os.environ.get("ANTHROPIC_API_KEY"):
            return "anthropic"
        return "offline"


settings = Settings()
