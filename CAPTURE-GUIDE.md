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

## If you only capture four

1. `Stage 3/01_Gateway Test Suite Passing.png`
2. `Stage 4/01_Schema Evolution Rules.png`
3. `Stage 1/04_Schema Validation Fault.png`
4. `Stage 2/03_Cache Invalidation On Write.png`

Those four cover all six technologies, and every one shows a **result** rather
than source code. Screenshots of an IDE prove you have an IDE; screenshots of
passing assertions prove the system works.

## Before capturing

Everything must be running:

```bash
docker compose up -d redis kong kafka schema-registry
```

Plus PostgreSQL and the three services on 8081, 8082, 8083.

## Sizing

Other project images are **1280×720**. Terminal captures are usually taller
than 16:9 — crop to a wide slice or pad to that ratio so the cards render
consistently.
