# Phase 7 — Distributed tracing

**OpenTelemetry** across every phase, exported to **Jaeger**. The phase where
the platform stops being six services that each work and becomes one system you
can watch.

- **Jaeger UI:** <http://localhost:16686>
- **Instrumented:** Phase 1 (SOAP), Phase 2 (REST), Phase 6 (GraphQL)
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
