# Screenshot Capture Guide

Shots for the portfolio page, with the exact command that produces each one.

**Destination:**
`jdoan5.github.io/images/Integration & API Platform/Stage N/`

**Naming:** `NN_Descriptive Name.png` — two-digit prefix, then a Title Case
description with spaces. Matches the existing convention used by the other
project pages.

**Before you start**

```bash
export PS1='$ '
```

That hides `/Users/<you>/...` from the prompt, which otherwise appears in every
terminal screenshot. Then `cd` into the repo so paths stay short.

Capture at roughly **100 columns**, dark terminal theme, cropped tight — no
desktop, no dock, no browser chrome beyond the page itself.

---

## Stage 1 — Contract-First SOAP

| File | What to capture |
|---|---|
| `01_Generated WSDL.png` | Browser at `localhost:8081/ws/inventory.wsdl` |
| `02_XSD Contract.png` | `inventory-v1.xsd` open in IDEA, showing the simpleTypes |
| `03_SOAP Request and Response.png` | A GetProduct call and its response |
| `04_Schema Validation Fault.png` | **The key shot** — an invalid SKU rejected |
| `05_Structured Fault Detail.png` | A business fault with `InventoryError` detail |
| `06_Contract Tests Passing.png` | `./mvnw test` — 15 tests |

```bash
# 03 — a successful call
curl -s -X POST http://localhost:8081/ws -H "Content-Type: text/xml;charset=UTF-8" -H 'SOAPAction: ""' --data-binary @phase1-soap-service/samples/01-get-product.xml | xmllint --format -
```

```bash
# 04 — validation rejects a malformed SKU before any Java runs
curl -s -X POST http://localhost:8081/ws -H "Content-Type: text/xml;charset=UTF-8" -H 'SOAPAction: ""' --data-binary @phase1-soap-service/samples/05-invalid-sku-FAILS.xml | xmllint --format -
```

```bash
# 05 — schema-defined fault detail
curl -s -X POST http://localhost:8081/ws -H "Content-Type: text/xml;charset=UTF-8" -H 'SOAPAction: ""' --data-binary @phase1-soap-service/samples/06-not-found-FAULT.xml | xmllint --format -
```

```bash
# 06 — the test suite
cd phase1-soap-service && ./mvnw test
```

---

## Stage 2 — REST Facade & Redis

| File | What to capture |
|---|---|
| `01_REST API Response.png` | JSON from `GET /api/v1/products/{sku}` |
| `02_Cache Keys In Redis.png` | `redis-cli KEYS` showing a cached entry |
| `03_Cache Invalidation On Write.png` | **The key shot** — key present, write, key gone |
| `04_Rate Limit Enforced.png` | The 200/429 split |
| `05_Idempotent Retry.png` | Same key twice, `replayed: true` |
| `06_Cache Statistics.png` | `/_cache/stats` with a real hit rate |

```bash
# 03 — the whole invalidation story in one capture
curl -s "http://localhost:8082/api/v1/stock/ELEC-AUD-001?warehouse=WH-EAST" | python3 -m json.tool && docker exec iap-redis redis-cli KEYS 'stock:*' && curl -s -X POST http://localhost:8082/api/v1/movements -H "Content-Type: application/json" -d '{"sku":"ELEC-AUD-001","warehouseCode":"WH-EAST","movementType":"IN","quantity":5}' | python3 -m json.tool && docker exec iap-redis redis-cli KEYS 'stock:*'
```

```bash
# 04 — 60 allowed, the rest refused
for i in $(seq 1 70); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/api/v1/products/ELEC-LAP-001 -H "X-Client-Id: demo"; done | sort | uniq -c
```

```bash
# 05 — a retry that does not double-count
curl -s -X POST http://localhost:8082/api/v1/movements -H "Content-Type: application/json" -H "Idempotency-Key: shot-1" -d '{"sku":"ELEC-AUD-001","warehouseCode":"WH-EAST","movementType":"IN","quantity":5}' | python3 -m json.tool
```

---

## Stage 3 — Kong API Gateway

| File | What to capture |
|---|---|
| `01_Gateway Test Suite Passing.png` | **The strongest shot in the project** — 14 green PASS |
| `02_Rejected Without API Key.png` | 401 then 200 |
| `03_Gateway Response Headers.png` | `X-Gateway`, `X-Correlation-ID`, `X-RateLimit-*` |
| `04_Declarative Configuration.png` | `kong.yml` in the editor |
| `05_Rate Limiting At The Edge.png` | 200s then 429s through the proxy |
| `06_Prometheus Metrics.png` | `localhost:8001/metrics` |

```bash
# 01 — run the whole suite
./phase3-gateway/test-gateway.sh
```

```bash
# 02 — auth enforced outside the application
curl -i -s http://localhost:8000/api/v1/products/ELEC-LAP-001 | head -5 && curl -i -s -H "apikey: local-demo-key-mobile" http://localhost:8000/api/v1/products/ELEC-LAP-001 | head -5
```

```bash
# 03 — headers added and version disclosure removed
curl -s -D- -o /dev/null -H "apikey: local-demo-key-mobile" http://localhost:8000/api/v1/products/ELEC-LAP-001
```

**Note:** the suite burns the rate limit, so wait ~60s between runs of 01 and 05
or they interfere.

---

## Stage 4 — Kafka, Avro & Schema Registry

| File | What to capture |
|---|---|
| `01_Schema Evolution Rules.png` | **The most interview-relevant shot** — accept / reject / accept |
| `02_Outbox Table.png` | `event_outbox` in DataGrip, pending vs published |
| `03_Event Pipeline Status.png` | `/events/status` |
| `04_Low Stock Alert Fired.png` | The alert log line |
| `05_Idempotent Replay.png` | Totals unchanged, `duplicatesIgnored` non-zero |
| `06_Registered Avro Schema.png` | The subject and its schema from the registry |

```bash
# 01 — the registry accepting and refusing changes
./phase4-events/schema-evolution-demo.sh
```

```bash
# 03 — outbox, relay, and both consumers
curl -s http://localhost:8083/events/status | python3 -m json.tool
```

```bash
# 05 — replay everything, totals must not move
psql -d inventory_mgmt -c "SELECT sku, units_in, units_out FROM movement_daily_totals ORDER BY sku;" && psql -d inventory_mgmt -c "UPDATE event_outbox SET published_at = NULL;" && sleep 12 && psql -d inventory_mgmt -c "SELECT sku, units_in, units_out FROM movement_daily_totals ORDER BY sku;" && curl -s http://localhost:8083/events/status | python3 -m json.tool
```

```bash
# 06 — the registered contract
curl -s http://localhost:8085/subjects && curl -s http://localhost:8085/subjects/inventory.stock-movement.v1-value/versions/1 | python3 -m json.tool
```

---

## Stage 5 — MCP Server & LangGraph Agent

Run everything from `phase5-mcp-agent/`. All six shots work with **no API key** —
the scripted offline model drives the real tools, so the plumbing on screen is
genuine even though the prose is not.

| File | What to capture |
|---|---|
| `01_Verification Suite Passing.png` | **The strongest shot** — 39 offline tests + 8 live checks |
| `02_The Contract A Model Sees.png` | Tools with annotations, plus resources and prompts |
| `03_Human Approval Before A Write.png` | **The most interview-relevant shot** — the graph paused mid-tool-call |
| `04_Idempotent Retry.png` | The same call twice, one database row |
| `05_Agent Tool Trace.png` | The agent choosing tools against live data |
| `06_Attached To Claude Code.png` | Claude Code calling these tools directly |

```bash
# 01 — the whole suite, offline guards and live platform
./phase5-mcp-agent/verify.sh
```

```bash
# 02 — the contract, rendered from the tool functions themselves
cd phase5-mcp-agent && ./.venv/bin/python contract.py
```

```bash
# 03 — answer N. The write is refused and the stock does not move.
cd phase5-mcp-agent && ./.venv/bin/python -m agent.cli "record 5 units OUT of ELEC-LAP-001 at WH-EAST"
```

```bash
# 04 — identical calls collapse to one movement; the second says replayed: true
cd phase5-mcp-agent && ./.venv/bin/python live_check.py && ./verify.sh 2>&1 | grep -A1 replayed
```

```bash
# 05 — the trace, on live data
cd phase5-mcp-agent && ./.venv/bin/python -m agent.cli "what needs restocking?"
```

**Shot 03 is the one to get right.** The approval banner prints the full
arguments and the graph is genuinely suspended underneath it — answering `N`
returns a refusal the model then explains, and the database is untouched. Show
the `[y/N]` prompt itself, not just the outcome.

**Shot 06** needs no command. Register the server, then ask Claude Code
*"which SKUs are below their reorder point?"* and capture it calling the tools:

```bash
cd phase5-mcp-agent && claude mcp add inventory -- $(pwd)/.venv/bin/python -m inventory_mcp
```

**Note on re-runs:** `verify.sh` records one real `ADJUSTMENT` of 1 unit per run,
and shot 03 moves 5 units if you answer `Y`. Both are deliberate — a write that
left no trace would not be proving anything — but the numbers on screen will
differ from the ones in this guide.

---

## Stage 6 — GraphQL Facade

Needs the facade on `:8086` and the SOAP service on `:8081`. Kong is optional.

| File | What to capture |
|---|---|
| `01_Verification Suite Passing.png` | **The strongest shot** — 13 green PASS |
| `02_The N Plus One, Measured.png` | **The most interview-relevant shot** — 1 call vs 7, same URL |
| `03_Over Budget Query Refused.png` | Complexity exceeded, and zero backend calls spent |
| `04_Schema In GraphiQL.png` | The schema browsing its own documentation |
| `05_Invalid Enum Rejected.png` | `WH_NYC` refused at validation — the Phase 5 bug, impossible |
| `06_Deprecated Not Versioned.png` | `suggestedOrderQty` served and marked deprecated |

```bash
# 01 — the whole suite
./phase6-graphql/test-graphql.sh
```

```bash
# 02 — the measurement. Reset, run cheap, read; reset, run nested, read.
curl -s -X DELETE localhost:8086/diagnostics/backend-calls >/dev/null && curl -s -X POST localhost:8086/graphql -H 'Content-Type: application/json' -d '{"query":"{ lowStock { sku quantity } }"}' >/dev/null && echo "cheap:     $(curl -s localhost:8086/diagnostics/backend-calls)" && curl -s -X DELETE localhost:8086/diagnostics/backend-calls >/dev/null && curl -s -X POST localhost:8086/graphql -H 'Content-Type: application/json' -d '{"query":"{ lowStock { sku quantity product { name reorderQuantity } } }"}' >/dev/null && echo "one field more: $(curl -s localhost:8086/diagnostics/backend-calls)"
```

```bash
# 03 — refused before execution, so it costs nothing
curl -s -X DELETE localhost:8086/diagnostics/backend-calls >/dev/null && curl -s -X POST localhost:8086/graphql -H 'Content-Type: application/json' -d '{"query":"{ lowStock { sku product { name stockLevels { warehouseCode quantity } } } }"}' | python3 -m json.tool && echo "backend calls spent: $(curl -s localhost:8086/diagnostics/backend-calls)"
```

```bash
# 05 — the value set is in the contract, so this is a query error
curl -s -X POST localhost:8086/graphql -H 'Content-Type: application/json' -d '{"query":"{ lowStock(warehouse: WH_NYC) { sku } }"}' | python3 -m json.tool
```

```bash
# 06 — deprecated, still served, and discoverable by introspection
curl -s -X POST localhost:8086/graphql -H 'Content-Type: application/json' -d '{"query":"{ __type(name:\"LowStockItem\") { fields(includeDeprecated:true) { name isDeprecated deprecationReason } } }"}' | python3 -m json.tool
```

**Shot 04** is a browser shot: <http://localhost:8086/graphiql>. Open the Docs
panel and show a field's description — the point is that the contract documents
itself, with no separate spec file to drift.

**Shot 02 is the one to get right.** Put both numbers in one frame. The whole
argument of this phase is that `1` and `7` came from the same URL, the same HTTP
method, and one tick of the same rate limit.

---

## If you only capture six

1. `Stage 4/01_Schema Evolution Rules.png`
2. `Stage 6/02_The N Plus One, Measured.png`
3. `Stage 5/03_Human Approval Before A Write.png`
4. `Stage 3/01_Gateway Test Suite Passing.png`
5. `Stage 1/04_Schema Validation Fault.png`
6. `Stage 2/03_Cache Invalidation On Write.png`

Those six cover every technology in the project, and each one shows a
**result** rather than source code. Screenshots of an IDE prove you have an IDE;
screenshots of passing assertions prove the system works.

The first three are the ones that start a conversation: a registry refusing a
breaking change, a gateway that cannot see a 7x cost difference, and an agent
stopped mid-write waiting for a human.

## Before capturing

Everything must be running:

```bash
docker compose up -d redis kong kafka schema-registry
```

Plus PostgreSQL and the services on 8081, 8082, 8083 and 8086. Phase 5 needs no
extra process for shots 01-05 — the agent spawns the MCP server over stdio
itself. Only a remote client needs `./.venv/bin/python -m inventory_mcp --http`
on 8084.

## Sizing

Other project images are **1280×720**. Terminal captures are usually taller
than 16:9 — crop to a wide slice or pad to that ratio so the cards render
consistently.
