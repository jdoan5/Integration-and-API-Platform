# Phase 2 — REST Facade + Redis

A REST API in front of the Phase 1 SOAP service, with Redis for caching, rate
limiting, and idempotency.

- **Stack:** Java 21, Spring Boot 4.1.1, Spring-WS client, Redis (Lettuce)
- **Port:** 8082 (SOAP service must be on 8081)
- **Contract:** [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml)

## The pattern: strangler fig

New consumers get clean JSON; the SOAP contract keeps serving the integrations
that already exist. Nothing is rewritten and the two coexist indefinitely.
Eventually the SOAP service can be replaced behind the facade without any
consumer noticing.

Note the API is **not** a 1:1 translation. SOAP models verbs (`GetProduct`);
REST models resources (`GET /products/{sku}`). A facade that merely renamed the
operations would inherit the old design and gain nothing.

## Run it

```bash
docker compose up -d redis
```

Then start Phase 1 on 8081, and this on 8082:

```bash
cd phase2-rest-facade && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

Requires: PostgreSQL (Postgres.app), Redis (Docker), and the Phase 1 service.

## Try it

```bash
curl -s http://localhost:8082/api/v1/products/ELEC-LAP-001 | python3 -m json.tool
```

```bash
curl -s "http://localhost:8082/api/v1/stock/ELEC-AUD-001?warehouse=WH-EAST" | python3 -m json.tool
```

```bash
curl -s -X POST http://localhost:8082/api/v1/movements -H "Content-Type: application/json" -d '{"sku":"ELEC-AUD-001","warehouseCode":"WH-EAST","movementType":"IN","quantity":20}' | python3 -m json.tool
```

## The four things worth understanding

### 1. Cache-aside, and the invalidation that goes with it

`GET /stock/{sku}` caches for 30s. `POST /movements` **deletes** every
`stock:{sku}:*` key it affected. Watch it:

```bash
curl -s "http://localhost:8082/api/v1/stock/ELEC-AUD-001?warehouse=WH-EAST" && docker exec iap-redis redis-cli KEYS 'stock:*'
```

Record a movement, then check `KEYS 'stock:*'` again — the key is gone, so the
next read fetches fresh data instead of serving a stale number until the TTL
expires. **Verified working:** cached 160 → wrote +20 → key evicted → next read
returned 180.

Comment out `invalidateStock(...)` in `InventoryService` and repeat the
experiment. Seeing 160 come back after the write is worth more than any
explanation of why cache invalidation is hard.

TTLs differ on purpose: products 5 minutes (rarely change), stock 30 seconds
(changes constantly). Choosing a TTL means deciding how stale you can afford
to be.

### 2. Why the rate limiter is a Lua script

The naive version has a race:

```
count = INCR key
if count == 1: EXPIRE key window
```

Two clients can both see `count == 1`, or a crash between the two commands
leaves a key with **no expiry** — permanently locking that caller out. Redis
runs Lua atomically, making `INCR` + `EXPIRE` indivisible.

Redis (rather than in-memory state) is also what makes the limit correct across
multiple instances — the same reason Kong's rate-limiting plugin uses Redis in
Phase 3.

**Verified:** 70 requests against a 60/min limit → exactly 60 × `200` and
10 × `429`, with other clients unaffected.

### 3. Idempotency keys

A client that times out and retries would otherwise double-count stock. Send
`Idempotency-Key: <anything-unique>` and the first result is stored for 24h;
replays return it with `"replayed": true` instead of writing again.

**Verified:** two POSTs with the same key both returned `movementId=15`, and
stock rose by 5 — not 10. This is how payment APIs avoid double charges.

### 4. Typed faults become HTTP status codes

Phase 1's schema-defined `InventoryError` detail pays off here.
`DetailCapturingFaultResolver` parses it into a plain Java exception carrying a
**code**, so the service maps `PRODUCT_NOT_FOUND` → `404` by branching on a
stable identifier rather than string-matching English prose that might be
reworded tomorrow.

## WSDL vs OpenAPI

Read [`openapi.yaml`](src/main/resources/openapi.yaml) beside Phase 1's
[`inventory-v1.xsd`](../phase1-soap-service/src/main/resources/xsd/inventory-v1.xsd).
Both are hand-written contracts; the header comment in the YAML compares them
line by line. Neither is better — SOAP puts more guarantees in the runtime at
the cost of ceremony, REST is lighter but pushes rigor onto you.

## Gotchas already handled here

- **Jackson 3 in Spring Boot 4** moved `ObjectMapper` from
  `com.fasterxml.jackson.databind` to `tools.jackson.databind`.
- **`SoapFaultDetailElement` has no `getText()`** — only `getResult()` (for
  writing) and `addText()`. Reading a fault detail means going back to the raw
  message, which is what `DetailCapturingFaultResolver` does.
- **The Initializr dependency id is `web-services`**, not `webservices`; the
  wrong one returns an error page instead of a zip.
