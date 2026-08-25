# Phase 3 — Kong API Gateway

Kong in **DB-less declarative mode** in front of both the REST facade (Phase 2)
and the SOAP service (Phase 1).

- **Config:** [`kong.yml`](kong.yml) — the entire gateway, in one reviewable file
- **Proxy:** `http://localhost:8000` (where consumers send traffic)
- **Admin/metrics:** `http://localhost:8001`

## Run it

```bash
docker compose up -d redis kong
```

Then start the Java services (SOAP on 8081, REST on 8082) and run the suite:

```bash
./phase3-gateway/test-gateway.sh
```

## The idea worth taking away

**Declarative configuration.** Everything Kong knows — services, routes,
plugins, consumers, credentials — lives in `kong.yml`. No admin-UI clicking,
no database, no drift between environments. The gateway is reviewed in pull
requests like application code.

Most gateway tutorials have you `POST` to the Admin API and leave you with a
configuration nobody can reproduce. DB-less mode is how it is actually run.

## What the gateway takes over

| Concern | Before | After |
|---|---|---|
| Authentication | not implemented | `key-auth` plugin — **zero app code** |
| Rate limiting | in the REST facade | `rate-limiting` plugin, shared Redis |
| Correlation IDs | none | `correlation-id`, generated at the edge |
| Header hygiene | leaked `Server` | `response-transformer` strips it |
| Metrics | per-service | `prometheus` across everything |

The application keeps its own rate limiter from Phase 2. That is not
redundancy to delete — it is **defence in depth**: the gateway protects the
platform, and the service protects itself from anything that bypasses the
gateway (an internal caller, a misrouted mesh, a future migration).

## Two deliberate design decisions

### 1. The SOAP route requires no API key

Look at `kong.yml`: `rest-facade` gets `key-auth`, `soap-service` does not.

That asymmetry is intentional. Legacy SOAP consumers cannot be asked to add an
API key overnight — they are running code you may not control. So they get
throttling and observability **now**, and authentication later once they have
been migrated. Meeting existing consumers where they are, rather than breaking
them, is most of what real gateway work involves.

### 2. Rate limiting uses `policy: redis`, not `policy: local`

With `local`, every Kong node keeps its own counter, so a three-node cluster
silently allows three times the intended limit. Redis gives all nodes one
shared counter.

Same lesson as the Lua script in Phase 2: **distributed limits need shared
state.** Kong writes to database `1` so its counters never collide with the
application's keys in database `0`.

`fault_tolerant: true` means that if Redis dies, Kong allows traffic through
rather than rejecting everything — a deliberate availability-over-strictness
choice worth understanding before you copy it.

## Try it by hand

```bash
curl -i http://localhost:8000/api/v1/products/ELEC-LAP-001
```

That returns **401** — no API key. Now with one:

```bash
curl -s -H "apikey: mobile-secret-key-001" http://localhost:8000/api/v1/products/ELEC-LAP-001 | python3 -m json.tool
```

Watch the rate limit engage (20/min for this consumer):

```bash
for i in $(seq 1 25); do curl -s -o /dev/null -w "%{http_code} " -H "apikey: mobile-secret-key-001" http://localhost:8000/api/v1/products/ELEC-LAP-001; done; echo
```

Prometheus metrics:

```bash
curl -s http://localhost:8001/metrics | grep kong_http_requests_total | head -5
```

## Exercises

1. **Add a consumer with a different quota.** Give `partner-integration` a
   `rate-limiting` plugin scoped to that consumer at 5/min, and prove the
   others are unaffected.
2. **Break an upstream.** Stop the REST facade and call through Kong. You get a
   502 — now add a health check and observe Kong marking the target unhealthy.
   This is where a gateway stops being a proxy and becomes resilience
   infrastructure.
3. **Swap `key-auth` for `jwt`.** Issue a token, set the consumer's public key,
   and compare the two auth models.
4. **Add `request-transformer`** to inject a header the SOAP service can log,
   so you can prove which calls arrived via the gateway.

## Gotchas

- **`host.docker.internal`** is how a container reaches a process on the macOS
  host. On Linux you need `extra_hosts: ["host.docker.internal:host-gateway"]`,
  which this compose file sets for portability.
- **`strip_path` matters.** The REST route uses `false` (upstream expects
  `/api/v1/...`); the SOAP route uses `true` so `/soap/ws` reaches the service
  as `/ws`.
- **Kong 3.x nests Redis config** under a `redis:` block. Older `redis_host`
  style keys are rejected by the 3.x schema.
- **Docker Desktop can wedge** a container in `Created` state where even
  `docker rm -f` hangs. Restarting Docker Desktop clears it.
