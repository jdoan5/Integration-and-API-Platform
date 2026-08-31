"""
OpenTelemetry for the MCP server.

Phase 7 traced the Java side and stopped at the gateway: a trace began when Kong
received a request and knew nothing about who sent it. For every other consumer
that is fine, because the consumer is a program someone wrote. For this one the
caller is a model deciding what to do next, and "which tool did it call, and
what did that cost" is the whole question.

So the server exports spans of its own, and instruments its httpx client - which
is the part that matters. httpx instrumentation writes the W3C `traceparent`
header on every outgoing call, so Kong, the REST facade and the SOAP service
adopt this trace instead of starting their own. The join happens for free
because everyone agreed on one header, which is the entire argument for a
standard.
"""

from __future__ import annotations

import logging
import os
import sys
from contextlib import contextmanager

from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.httpx import HTTPXClientInstrumentor
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.sampling import ALWAYS_OFF, ALWAYS_ON

from .config import settings

SERVICE_NAME = "mcp-inventory-server"

_started = False


def setup_tracing() -> None:
    """Configure the exporter once, and instrument httpx.

    Safe to call more than once; the second call is a no-op rather than a
    second provider quietly dropping half the spans.
    """
    global _started
    if _started:
        return
    _started = True

    enabled = settings.tracing_enabled
    provider = TracerProvider(
        resource=Resource.create({
            "service.name": SERVICE_NAME,
            "service.version": os.environ.get("MCP_VERSION", "0.1.0"),
        }),
        sampler=ALWAYS_ON if enabled else ALWAYS_OFF,
    )

    if enabled:
        # STDOUT IS THE PROTOCOL on stdio transport. An exporter that cannot
        # reach the collector logs about it, and a stray line on stdout corrupts
        # JSON-RPC and takes the whole session down with a parse error that
        # names no cause. Everything OTel says goes to stderr, and its own
        # logger is quietened so a missing Jaeger is a silence, not an outage.
        logging.getLogger("opentelemetry").addHandler(logging.StreamHandler(sys.stderr))
        logging.getLogger("opentelemetry").setLevel(logging.ERROR)

        provider.add_span_processor(
            BatchSpanProcessor(OTLPSpanExporter(endpoint=settings.otlp_endpoint))
        )

    trace.set_tracer_provider(provider)

    # THE LINE THAT JOINS THE TRACE TO THE JAVA SIDE. Without it the MCP server
    # reports healthy spans under its own trace id and the platform reports
    # healthy spans under another, which looks like everything working.
    HTTPXClientInstrumentor().instrument(tracer_provider=provider)


def tracer() -> trace.Tracer:
    return trace.get_tracer("inventory-mcp")


@contextmanager
def tool_span(name: str, **attributes: object):
    """A span around one MCP tool call.

    Named `mcp.tool <name>` so the waterfall reads as what the MODEL decided to
    do, with the HTTP and Redis spans underneath as what that decision cost.
    Without this the httpx spans are orphans: correct, joined to the platform,
    and with nothing saying which tool call caused them.
    """
    with tracer().start_as_current_span(f"mcp.tool {name}") as span:
        span.set_attribute("mcp.tool.name", name)
        for key, value in attributes.items():
            if value is not None:
                span.set_attribute(key, value)
        yield span
