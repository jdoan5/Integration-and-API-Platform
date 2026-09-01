# Phase 8 — Durable orchestration

A **stock transfer saga** on **Temporal**: goods leave one warehouse, travel,
and arrive at another — and if anything fails in between, they go back.

- **App:** [`src/main/java`](src/main/java) — Spring Boot 4, port `8087`
- **Temporal UI:** <http://localhost:8233>
- **Verify:** [`test-orchestration.sh`](test-orchestration.sh)

## Run it

```bash
docker compose up -d temporal
```

```bash
cd phase8-orchestration && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

```bash
./test-orchestration.sh
```

Needs the Phase 1 SOAP service, the Phase 2 facade and Kong, because the
activities move real stock.

## Choreography and orchestration, over one domain

Phase 4 already answered "how do parts of this system react to each other" with
**choreography**: a movement commits an event through the outbox, and
independent consumers react. Nobody is in charge. This phase answers a
different question — "who is responsible for finishing this?" — and the two are
not competing, they fail differently:

| | Phase 4 — choreography | Phase 8 — orchestration |
|---|---|---|
| Who owns the outcome | nobody | the workflow |
| Where state lives | implied by the event log | in the workflow, explicitly |
| Retries | consumer's own concern | declared per activity, survive the process |
| On failure | each consumer copes alone | compensations unwind, in reverse |
| "Where is it now?" | replay the log and infer | a query |
| Coupling | minimal | the workflow knows every step |
| Adding a step | deploy a new consumer | edit the workflow |

The last two rows are the honest cost. Orchestration re-centralises what
choreography spent effort decoupling: the workflow knows all five steps and
their order, and a new step means changing it. That is a real trade and worth
paying only when something has to *complete*.

## The state no single event represents

Start a transfer and ask where it is before approving:

```
{ "stage": "AWAITING_APPROVAL", "sku": "ELEC-LAP-001",
  "fromWarehouse": "WH-WEST", "toWarehouse": "WH-EAST",
  "quantity": 3, "decision": "PENDING", "note": "consignment CN-transfer" }
```

At that moment the stock has left `WH-WEST` and has not arrived at `WH-EAST`.
It is in **neither warehouse** — verified by the suite, `WH-WEST 16→13` while
`WH-EAST` stays at `67`.

Phase 4 has no event for that, and could not have one: *in transit* is the
**gap between** two events rather than one of them. Choreography can tell you
what happened; only something that owns the process can tell you where the
process is. That query is the whole argument for this phase.

## Compensation, written down

The saga registers each compensation immediately after the step it undoes:

```java
Dtos.MovementResult outbound = inventory.shipOut(...);

saga.addCompensation(() -> inventory.returnToSource(...));
```

Not at the end, and not in a `finally`. From that line onward, *any* failure —
a rejected approval, a timed-out human, an unavailable carrier — unwinds it
without anyone remembering to. The suite proves all three paths restore the
stock rather than only the happy one.

The workflow reads as ordinary sequential code and is nonetheless durable: the
worker can be killed between any two lines and resume on another with its local
variables intact, because Temporal replays history rather than keeping a thread
alive. Nothing here is persisted by hand.

## Retries meet idempotency, from the other side

Temporal **does not make retries safe. It makes them certain.** Every activity
can run more than once for one logical step, so each sends an
`Idempotency-Key` derived from the workflow id and the step name — never from
the attempt:

```java
movement(workflowId + ":ship-out", ...)
```

That is the Phase 5 lesson arriving from the opposite direction. There a
*model* held a write tool and might call it twice; here a *workflow engine*
guarantees it. Both are answered by the same endpoint the Phase 2 facade
already had, which is the third time in this project that a guard built for one
consumer turned out to be the right shape for another.

## Durable approval, versus Phase 5's

Phase 5 pauses a LangGraph agent for human approval with an in-memory
checkpointer: kill the process and the pending decision is gone. Here the wait
is `Workflow.await(APPROVAL_WINDOW, ...)` — no thread is blocked, nothing
polls, and if every process dies the timer survives in Temporal rather than in
a scheduler somebody has to keep running.

Same idea, different durability guarantee, and the difference is the entire
reason a workflow engine exists.

## Gotchas found building this

- **A dead artifact name, and a wrong conclusion drawn from it.** Searching
  Maven Central for `temporal-spring-boot-starter-alpha` returns 1.23.2, last
  published April 2024, pinning `spring-boot-dependencies` 2.7.x — which looks
  exactly like "the Spring integration is abandoned and cannot work on Boot 4",
  and that is what went into this file first.
  It is the wrong artifact. **The `-alpha` suffix was dropped at 1.24.0**, and
  `temporal-spring-boot-starter` is at 1.38.0, published three weeks ago, and
  documented as supporting Boot 2.x, 3.x and 4.x. The 2.7 pin in its POM is
  real and irrelevant: the `spring-boot-starter-parent` in this module wins the
  dependency-management fight, so no old Spring resolves.
  The hand-wired
  [`TemporalConfig`](src/main/java/com/jdoan/inventory/orchestration/config/TemporalConfig.java)
  stays, because thirty explicit lines suit one worker on one queue. But it is
  now a preference rather than a workaround — **a stale version number is a
  claim about an artifact, not about a project**, and the gap between those two
  is one rename wide.
- **The test suite competed with the workflow for the gateway's quota.** Kong
  allows a consumer 20 requests a minute. The suite polls stock repeatedly, the
  activities also call through Kong, and together they blew the limit: the
  activities got `429`, retries exhausted, and *every* transfer compensated.
  The behaviour was correct and the test was meaningless. Reads now go direct
  to the facade — an observer must not spend the quota of the thing it
  observes. The deeper point survives: **an orchestrator is a high-frequency
  consumer**, and a per-consumer limit sized for humans will throttle it.
- **`RestClient.Builder` is not auto-configured** on Boot 4 without the
  restclient starter, and the failure is a bean-not-found at startup rather than
  anything about HTTP. `RestClient.create(baseUrl)` is enough for one activity
  calling one API.
- **A boolean `approved` rendered a pending decision as `false`**, which reads
  as *rejected* — the opposite of the truth, on the one field an operator looks
  at while deciding whether to chase someone. It is a tri-state now:
  `PENDING` / `APPROVED` / `REJECTED`.
- **A compensated transfer reported `outboundMovementId: 0`**, hiding the fact
  that it really did move stock and then move it back. The ledger shows both
  movements; a result that mentions neither makes them inexplicable later.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8087` | HTTP port |
| `TEMPORAL_TARGET` | `127.0.0.1:7233` | Temporal frontend gRPC |
| `INVENTORY_API` | `http://localhost:8000` | Kong, so the workflow obeys the same policy as everyone else |
| `INVENTORY_API_KEY` | `local-demo-key-internal` | gateway consumer key |
| `OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | Phase 7 tracing |

## API

```bash
curl -X POST localhost:8087/transfers -H 'Content-Type: application/json' \
  -d '{"sku":"ELEC-LAP-001","fromWarehouse":"WH-WEST","toWarehouse":"WH-EAST","quantity":3,"simulate":""}'
```

| | |
|---|---|
| `POST /transfers` | start one; returns a `workflowId` |
| `GET /transfers/{id}` | **where is it now** |
| `POST /transfers/{id}/approve?by=` | durable signal |
| `POST /transfers/{id}/reject?reason=` | durable signal |
| `GET /transfers/{id}/result` | block until it finishes |

Pass `"simulate": "carrier-unavailable"` to force the compensation path. It is
deterministic on purpose — a random failure rate would be more realistic and
would make the verification suite flaky, which is worse.
