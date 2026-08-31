# Phase 7 — Distributed tracing

**OpenTelemetry** across every phase, exported to **Jaeger**. The phase where
the platform stops being six services that each work and becomes one system you
can watch.

- **Jaeger UI:** <http://localhost:16686>
- **Instrumented:** Phase 1 (SOAP), Phase 2 (REST), Phase 4 (events), Phase 5 (MCP server), Phase 6 (GraphQL)
- **Verify:** [`test-tracing.sh`](test-tracing.sh)

## Run it

```bash
docker compose up -d jaeger
```

Restart the Java services so they pick up the exporter, then:

```bash
./phase7-observability/test-tracing.sh
```

## Why this is not just "add a correlation ID"

Phase 3 already did that, and it was worth doing: one id carried from Kong
through every hop tells you **which** request you are looking at when you grep
four log files.

What it cannot tell you is what that request *did*. Not the order, not the
nesting, not where the time went, not that one hop happened six times. A
correlation id is a join key. A trace is a **structure** — spans with parents,
durations, and a causal order — and everything below is what that buys.

## The same bug, a second time

The repo's own README already lists this failure:

> **A distributed trace that silently died** at the REST facade. The gateway
> generated a correlation ID and the facade dropped it.

It happened again, in the same place, for the same reason. Spring's
observability auto-instruments the servlet layer and its HTTP clients, so most
hops join up for free — but `WebServiceTemplate` is not on that list, and
nothing puts `traceparent` on an outgoing SOAP call unless you do it yourself.

The failure looks like this, which is the problem:

```
graphql-facade          trace ed016d9f…   1 span
soap-inventory-service  trace aa1bfbeb…   1 span
```

Two healthy services. Two traces. No error anywhere, no log line, nothing to
alert on — and the moment you actually need to follow a slow request across
that hop, the data was never collected. Distributed tracing does not fail
loudly; it fails by being *slightly less useful than you assumed*, which is why
"we have tracing" is a claim worth testing rather than believing.

[`OutboundSoapHeaders`](../phase6-graphql/src/main/java/com/jdoan/inventory/graphql/soapclient/OutboundSoapHeaders.java)
is the fix, and it is deliberately a sibling of the Phase 2
`CorrelationIdPropagator` — the same class, for the same hop, two years of
telemetry fashion apart.

## What the trace shows that the counter could not

Phase 6 measured its N+1 with a counter and reported **7 backend calls**. That
number was correct. Here is the same query as a waterfall:

```
graphql-facade         http post /graphql              51.4ms  ████████████████
graphql-facade          graphql query                  50.5ms  ███████████████
graphql-facade          graphql field lowStock          8.6ms  ██
soap-inventory-service   http post                      5.0ms  █
graphql-facade           get                            0.9ms  █      ← redis
soap-inventory-service   http post                      3.0ms  █
graphql-facade           set                            1.5ms  █
graphql-facade           get                            0.8ms  █
soap-inventory-service   http post                      3.7ms  █
…
```

Two things are visible here that no counter could have told me:

**One.** The fan-out is a *sawtooth* — `get → SOAP → set`, repeating. That shape
is the cache-aside pattern executing per item, which is the N+1 in the form you
would actually recognise in production.

**Two, and this is the one that mattered:** the SOAP spans are **strictly
sequential**. They do not overlap. The batch loader was written with
`.parallel()` and both the code comment and the Phase 6 README claimed the
fetches ran concurrently. They never did — no pool threads, no overlap, just six
calls in a row.

So the `.parallel()` was deleted rather than the sentence quietly softened.
Genuine concurrency there needs an executor *and* trace-context propagation
across the thread boundary, and six 3ms calls do not justify either.

**A counter can only confirm what you thought to count. The trace falsified a
claim the code was making about itself.** That is the whole argument for this
phase, and I would not have found it by reading the code, because I wrote the
code.

## The agent's tool call, all the way down

Phase 5's MCP server exports spans too, so a trace now starts at the tool the
model chose and ends in PostgreSQL:

```
mcp.tool get_product                     35.9ms   ← what the model decided to do
  GET                                    35.3ms   ← httpx, carrying traceparent
    http get /api/v1/products/{sku}      23.6ms   ← REST facade, through Kong
      evalsha                             1.1ms   ← the Lua rate limiter
      get · get                                   ← cache-aside lookups
      http post                           9.5ms   ← SOAP service
      set                                 0.9ms   ← cache write
```

One line does the joining — `HTTPXClientInstrumentor().instrument()`. Everything
below `GET` is Java, instrumented separately, and it attaches itself because
both sides agreed on `traceparent`. Nobody wrote glue.

The `mcp.tool` span on top is the part worth having: it names the decision, so
the waterfall reads as *what the model did* with *what that cost* nested
underneath, rather than as a pile of orphaned HTTP calls.

## Where the trace starts, and why not earlier

The root span is the **MCP server**, not the LangGraph agent — so the model's
own thinking time is outside the trace. That is a limitation of the plumbing
today, not a design choice, and it is worth being precise about because the
obvious summary ("MCP has no trace propagation") is wrong:

| | |
|---|---|
| The MCP protocol | **has** a `_meta` slot on tool calls |
| The Python SDK, client side | **exposes** it — `CallToolRequestParams.meta` |
| The Python SDK, server side | **exposes** it — `ctx.request_context.meta` |
| `langchain-mcp-adapters` | gives an interceptor only `headers` — and **stdio has no headers** |

So both ends of the protocol could carry a `traceparent` and neither end is
missing the field. The gap is one library's interceptor surface, on the one
transport that matters here: stdio is what this project uses and what Claude
Desktop launches.

The available hack — smuggling the trace id in as a tool *argument* — was
rejected. Tool arguments are part of the schema the model reads, and putting
telemetry plumbing in the contract would corrupt the very thing Phase 5 is
about.

What is lost is the agent's own latency, which LangSmith or an LLM-level tracer
measures anyway. What is kept is every hop that touches infrastructure, which is
the expensive part and the part that pages someone.

## Two traces, and why that is correct

The event path traces too, and context crosses Kafka:

```
task outboxRelay.relay                      internal
  inventory.stock-movement.v1 send          producer
    inventory.stock-movement.v1 process     consumer   ← low-stock alerter
    inventory.stock-movement.v1 process     consumer   ← read-model projector
```

A broker is not an HTTP hop: the producer has to write `traceparent` into the
record headers and the consumer has to read it back. Spring does both once
`spring.kafka.template.observation-enabled` and
`spring.kafka.listener.observation-enabled` are set — and the trace id then
shows up in the consumer's own log MDC, which is the join between traces and
logs people usually build by hand.

**But this is a SECOND trace, not a continuation of the first.** The HTTP write
that caused it lives in its own trace and stops at PostgreSQL. That is not a
dropped hop — it is the transactional outbox doing its job. The relay reads a
row on a schedule, possibly minutes later, in a different transaction, with no
in-process context to inherit. Decoupling the write from the publish is the
entire point of the pattern; a trace that spanned both would mean the publish
was still attached to the request, which is the dual-write bug Phase 4 exists to
avoid.

Joining them anyway is possible — store `traceparent` on the outbox row and
restore it in the relay as a span link. It is deliberately not done here,
because in this design the outbox row is written by a **PostgreSQL trigger**,
so the application never sees it being created. Threading telemetry through a
database trigger to make one waterfall prettier is a bad trade, and "these are
two traces linked by an event id" is the honest description of an asynchronous
system.

## What you get without writing any code

The Redis spans in that waterfall were free. Nobody instrumented the cache:

- `evalsha` — the Lua token-bucket rate limiter from Phase 2
- `get` / `set` — cache-aside on products
- `http post` — every SOAP call

That is the actual case for OpenTelemetry over a bespoke logging convention.
The instrumentation lives in the libraries, so the platform got observable
faster than anyone could have written the logging for it.

## Sampling, stated honestly

`management.tracing.sampling.probability=1.0` here, because this is a laptop and
a trace you cannot find proves nothing.

It is also the first dial you turn down in production, and turning it down
creates a real problem: head-based sampling decides at the *start* of a request,
before anyone knows whether it will be slow or fail. Sample at 1% and you keep a
random 1%, which is almost never the 1% you wanted. That is what tail-based
sampling exists to fix, and it needs a collector holding spans in memory until
the trace completes — infrastructure this phase does not build, but the tradeoff
belongs in the README rather than being discovered later.

## Gotchas found building this

- **Spring Boot 4 renamed the export property, and ignores the old one in
  silence.** Boot 3's `management.otlp.tracing.endpoint` produces no warning, no
  error, and no traces. The Boot 4 name is
  `management.opentelemetry.tracing.export.otlp.endpoint`. Same family as every
  other Boot 4 relocation, and the failure mode is the worst kind: everything
  starts cleanly and the data just is not there.
- **A hand-built bean opts out of the properties that configured the bean it
  replaced.** `spring.kafka.listener.observation-enabled=true` configures the
  container factory Boot *auto-configures*; this project builds its own for the
  Avro deserialiser, so the flag did nothing. The producer emitted spans, the
  consumers processed records, and there were simply no consumer spans — no
  warning, no error, nothing to grep. One line on the factory
  (`getContainerProperties().setObservationEnabled(true)`) fixed it.
- **`WebServiceTemplate` is not auto-instrumented**, as above. Anything not
  speaking through `RestClient`/`WebClient`/the servlet stack needs the header
  injected by hand.
- **Read the trace context on the calling thread.** The
  `WebServiceMessageCallback` runs later; a thread-local read inside it can
  easily belong to a different thread or to nothing at all.
- **Jaeger ingests asynchronously**, so every assertion in `test-tracing.sh`
  sleeps before querying. A trace that has not landed yet looks exactly like a
  trace that never will, which makes for a beautifully flaky test suite if you
  do not account for it.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | Where spans are sent |
| `TRACE_SAMPLE` | `1.0` | Sampling probability |
| `JAEGER_URL` | `http://localhost:16686` | Used by the verification script |
| `TRACE_SETTLE` | `8` | Seconds to wait for ingestion before asserting |

Any OTLP-compatible backend works in place of Jaeger — Grafana Tempo, Honeycomb,
Datadog, Splunk Observability, AppDynamics. Nothing above is Jaeger-specific,
which is the point of exporting to a standard rather than to a vendor.
