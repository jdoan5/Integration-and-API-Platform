# Phase 6 — GraphQL facade

A **GraphQL** API over the same inventory domain, and the phase where three
things the platform relied on stop working.

- **App:** [`src/main/java`](src/main/java) — Spring for GraphQL, port `8086`
- **Contract:** [`inventory.graphqls`](src/main/resources/graphql/inventory.graphqls)
- **GraphiQL:** <http://localhost:8086/graphiql>

## Run it

```bash
cd phase6-graphql && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

```bash
./test-graphql.sh
```

Requires the Phase 1 SOAP service on `:8081`, Redis, and PostgreSQL. Kong is
optional — section 6 of the script skips without it.

## What breaks, and what replaces it

GraphQL is not "REST but nicer". It moves query composition from the server to
the client, and that one change invalidates three mechanisms this project spent
five phases building.

| What Phase | Relied on | Why GraphQL breaks it | What replaces it |
|---|---|---|---|
| 2 — Redis cache | The URL as the cache key | One URL, and the response shape varies per caller | Cache the **entity** by SKU, not the response |
| 3 — Kong rate limit | Every request costing about the same | One query can cost 1 backend call or 50 | **Query cost analysis**, in the server |
| 1 / 2 / 4 — versioning | A namespace, a URL path, a registry subject | GraphQL has no versions | `@deprecated` plus usage tracking |

Each break has a *different* fix. Knowing which mechanism replaces which is the
actual skill here — reaching for a DataLoader when the problem is cost, or for a
rate limit when the problem is duplication, is how teams end up with all three
and none of them working.

## The measurement that makes the point

`test-graphql.sh` resets a backend-call counter, runs one query, and reads it
back. These numbers are produced, not claimed:

```
{ lowStock { sku quantity } }                              1 backend call
{ lowStock { sku quantity product { name ... } } }         7 backend calls
{ lowStock { sku product { name stockLevels { ... } } } }  REFUSED, 0 calls
```

**All three are one HTTP POST to one URL.** Kong counted `1` against the rate
limit for each of the first two, because counting requests is all a gateway can
do without parsing and understanding the query body — and that is the
application's job, not the gateway's.

The third is refused by `MaxQueryComplexityInstrumentation` after validation and
*before* execution, so it costs zero backend calls rather than being killed
partway through having already hammered the SOAP service.

## Cost analysis, done properly

graphql-java's default complexity calculator adds 1 per field **in the
document**, so it cannot tell `lowStock { sku }` returning one row from the same
query returning six hundred. Both score 2. A limit built on that number looks
like protection and is not — the query that actually hurts you is a *narrow*
selection over a *long* list, and the default scores it as cheap.

[`ListAwareComplexityCalculator`](src/main/java/com/jdoan/inventory/graphql/config/ListAwareComplexityCalculator.java)
multiplies a list field's children by how many elements it expects, from the
`limit` argument where the schema has one. With that in place the scores
separate properly:

| Query | Score | Verdict |
|---|---|---|
| `lowStock(limit:5) { sku }` | 11 | accepted |
| `lowStock { sku }` | 51 | accepted |
| `lowStock { sku quantity product { … } }` | 251 | accepted, and costs 7 calls |
| `lowStock { product { stockLevels { … } } }` | 2701 | **refused** |

That number is an **estimate** and cannot be anything else — the true count is
only known after the backend answers, which is far too late to refuse the query.
Cost analysis is a bet placed before execution about what execution will cost.
The honest framing is that you are choosing how wrong to be and in which
direction: overestimate and you refuse legitimate queries, underestimate and you
get paged.

## What batching actually buys you

`LowStockItem.product` is resolved with `@BatchMapping`, so it is called once
for the whole list instead of once per item. Worth being precise about what that
fixes, because "add a DataLoader" is repeated far more often than it is
measured:

- It **removes duplicate work** — the same SKU low in three warehouses is
  fetched once.
- It **issues the calls from one place** instead of scattering them through the
  resolution tree, which is what makes them visible as one unit in a trace.
- It does **not** make an expensive query cheap. The SOAP service has no bulk
  "get these products" operation, so six distinct SKUs still cost six backend
  calls.
- It does **not** run them concurrently. This loop used a parallel stream and
  this list used to claim parallelism; Phase 7 traced it and found six strictly
  sequential spans. The claim was wrong, so the `.parallel()` went rather than
  the sentence being quietly softened.

Which is the honest reason this phase needs cost analysis *as well*. Batching is
the answer to duplication; refusing the query is the answer to cost. On this
project's seed data the low-stock list happens to contain six distinct SKUs, so
the dedup path saves nothing at all — a fact worth stating rather than hiding
behind a benchmark chosen to flatter it.

## The bug from Phase 5 that cannot happen here

Phase 5 found, by running against live data, that asking the REST facade for
stock at `WH-NYC` returns `200 []` — indistinguishable from "stocked here,
quantity zero" — and the agent duly reported "no stock at WH-NYC" about a
warehouse that does not exist.

Here `WarehouseCode` is an **enum in the schema**, so `WH_NYC` is rejected
during query validation, before a single resolver runs. The value set moved from
documentation into the contract, and a whole class of silent wrong answer went
with it.

That is the same argument the XSD made in Phase 1, in a fifth protocol.

## Gotchas found building this

- **The depth limit broke the schema browser, and the tests stayed green.**
  GraphiQL's first act is the full introspection query — 15 levels deep — so a
  depth limit of 10 rejected it and the UI loaded with no docs panel and no
  autocomplete. The suite's own introspection check was shallow enough to pass,
  which is the worst possible combination: a green suite and a broken
  developer-facing surface. Raising the limit past 15 would have "fixed" it and
  gutted the protection. Introspection is one fixed query whose cost is a
  property of the schema rather than the caller, so it is exempted
  ([`IntrospectionAwareLimits`](src/main/java/com/jdoan/inventory/graphql/config/IntrospectionAwareLimits.java))
  and the suite now sends the real thing. In production you turn introspection
  off entirely rather than making a depth limit do that job.
- **The cache failed open where it had to fail closed.** Docker stopped
  mid-session and Redis went with it. Reads carried on correctly — the cache is
  an optimisation. But the idempotency lookup used the same helper, so an
  unreachable Redis looked like "no prior call" and two identical mutations
  wrote two movements (`movementId` 42, then 43) while promising to be safe to
  retry. That is the Phase 2 cache bug inside out: there it became a hard
  dependency and reads broke; here it stayed soft where it must be hard and
  writes duplicated in silence. Failing open is right for reads and dangerous
  for writes, and the two paths now use different code — a caller who supplies
  an `idempotencyKey` gets a refusal rather than a duplicate.
- **Spring Boot 4 ships Jackson 3, and Jackson 3 moved its root package.**
  `com.fasterxml.jackson.databind` → `tools.jackson.databind`, and
  `JacksonException` is now **unchecked**. Every Jackson snippet written before
  2026 has the wrong import, and the compiler error says only "package does not
  exist" — pointing at your import rather than at the relocation.
- **A GraphQL enum value cannot contain a hyphen**, so the domain's `WH-EAST`
  cannot appear in the schema verbatim. The alternative was typing the field as
  `String` and losing the closed value set — exactly the guarantee this project
  spends five phases defending. Paying for a mapping function is the right
  trade, but it is a real cost of moving a contract between protocols.
- **The default complexity calculator is list-blind**, as above. This is the one
  most likely to ship as security theatre.
- **Kong's declarative validator crashed on its own error message.** A plugin
  scoped to a service that does not exist produced
  `attempt to concatenate local 'k' (a userdata value)` from inside
  `pretty_print_error`, and no indication of the actual problem. Validate
  `kong.yml` by parsing it and checking every `service:` reference resolves,
  because Kong will not tell you.
- **The reference docs name the wrong test dependency.** Spring for GraphQL's
  documentation still shows `org.springframework.graphql:spring-graphql-test`
  with a hardcoded `<version>`. On Boot 4 the right artifact is
  `spring-boot-starter-graphql-test`, which the parent version-manages and which
  matches the `*-test` starter naming the rest of this repo already uses.
  Hand-adding the other one works, and silently opts that one dependency out of
  the BOM.
- **Boot 4 relocated every autoconfigure package.** `org.springframework.boot.autoconfigure.graphql.*`
  is now `org.springframework.boot.graphql.autoconfigure.*`, and the test
  annotations moved too. Any GraphQL snippet written against Boot 3 has the
  wrong import, and the compiler only says the package does not exist.
- **`@BatchMapping` cannot batch a field that takes an argument.** `stockLevels`
  takes an optional `warehouse`, and two items asking for different warehouses
  cannot share a call — DataLoader batches by key, and an argument is part of
  the key. It stays a plain `@SchemaMapping`, and that is correct rather than a
  missed optimisation.

## Schema mapping inspection

Spring for GraphQL checks at startup that the schema and the controllers agree,
and reports the gaps. On this module it reports nothing:

```
GraphQL schema inspection:
	Unmapped fields: {}
	Unmapped registrations: {}
	Unmapped arguments: {}
	Field nullness errors: {}
	Argument nullness errors: {}
	Skipped types: []
```

Every schema field has a resolver, every resolver maps to a real field, and
every argument binds. It is the closest thing GraphQL has to the build-time
guarantee the XSD gives Phase 1 — and unlike the XSD it runs at startup rather
than at compile time, so it catches a rename that compiles perfectly well.

The two **nullness** lines are new in Spring for GraphQL 2.0: it now compares
SDL nullability (`Product` vs `Product!`) against the nullability of the Java
method returning it. A field declared non-null in the schema whose resolver can
return `null` is a runtime error waiting for the right data — this reports it at
startup instead, and it is the check most likely to light up on a schema written
before the upgrade.

## The public demo

**Live:** <https://inventory-graphql-demo-ihnb3flw5a-uc.a.run.app/graphiql?path=/graphql>

The whole stack needs PostgreSQL, a SOAP service, Redis, Kafka and a gateway
before it answers anything. That is the right shape for the project and the
wrong shape for a link someone clicks once, so there is a `demo` profile that
runs this facade alone:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

```bash
docker build -t inventory-graphql-demo . && docker run --rm -p 8080:8080 inventory-graphql-demo
```

```bash
./deploy-demo.sh --dry-run     # then without the flag to publish to Cloud Run
```

One container, ~6 second cold start, scale-to-zero.
[`DemoInventoryBackend`](src/main/java/com/jdoan/inventory/graphql/api/DemoInventoryBackend.java)
serves a snapshot exported from the real database instead of calling SOAP.

**What stays real, because a demo that blurs this is worse than no demo:**

| | |
|---|---|
| The schema | The same `.graphqls`, resolvers, and query validation |
| Cost analysis | The same instrumentation — the expensive query still scores **2701** and is still refused |
| The N+1 | The demo backend calls the same counter, so `lowStock { product { … } }` still measures **1 vs 7** |
| The data | A snapshot of `inventory_mgmt` — 15 products, 45 stock rows, 3 warehouses |
| The write | **Refused**, with a typed `DEMO_READ_ONLY` error |

The mutation is deliberately left *in the schema*. Hiding it would misrepresent
the contract; refusing it at runtime with an explanation is the honest version.

This is also why [`InventoryBackend`](src/main/java/com/jdoan/inventory/graphql/api/InventoryBackend.java)
exists at all. Until the demo there was one backend and an interface would have
been abstraction for its own sake. The seam is expressed in the GraphQL types
rather than the JAXB ones, so the fixture implementation has never heard of
SOAP — which is what keeps it a second implementation rather than a mock.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8086` | HTTP port |
| `SOAP_URI` | `http://localhost:8081/ws` | Phase 1 backend |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Entity cache |
| `GRAPHQL_CACHE` | `true` | Set `false` to measure uncached call counts |
| `GRAPHQL_MAX_DEPTH` | `10` | Depth limit |
| `GRAPHQL_MAX_COMPLEXITY` | `300` | Complexity budget |
| `GRAPHQL_DEFAULT_PAGE_SIZE` | `25` | Assumed list size when a field has no `limit` |

## Tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

11 unit tests covering the warehouse-code round trip, the complexity scoring, and
the idempotency store failing closed —
including that a `[Type!]!` is still recognised as a list, which is the mistake
that would make every list score as a scalar and quietly disable the limit.

The behavioural assertions live in `test-graphql.sh`, against a running stack,
because a mocked GraphQL server cannot tell you how many backend calls a query
really caused.
