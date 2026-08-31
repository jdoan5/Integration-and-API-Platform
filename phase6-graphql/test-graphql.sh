#!/usr/bin/env bash
# ============================================================================
# test-graphql.sh  -  exercise every GraphQL behaviour this phase configures.
#
#   ./test-graphql.sh
#
# Requires: the GraphQL facade on :8086, the SOAP service on :8081, Redis, and
# PostgreSQL. Kong on :8000 is optional - section 5 is skipped without it.
#
# The backend-call counts printed here are MEASURED, not asserted in prose.
# /diagnostics/backend-calls is reset before each query and read after it, so
# the numbers in README.md are the numbers this script produced.
#
# NOTE ON RE-RUNS: section 4 records a real ADJUSTMENT of 1 unit under a fresh
# idempotency key, so every run adds one movement to the ledger. Same trade-off
# as test-gateway.sh and phase5's verify.sh: a write that left no trace would
# not be proving anything.
# ============================================================================
set -uo pipefail

GQL=${GRAPHQL_URL:-http://localhost:8086}
PROXY=${KONG_URL:-http://localhost:8000}
KEY=${KONG_KEY:-local-demo-key-internal}

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; }
banner() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
FAILED=0

# --- helpers ---------------------------------------------------------------
gql() {   # gql <query>  -> raw JSON response
    curl -s -X POST "$GQL/graphql" -H 'Content-Type: application/json' \
         -d "{\"query\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$1")}"
}
reset_counters() {
    curl -s -X DELETE "$GQL/diagnostics/backend-calls" >/dev/null
    curl -s -X DELETE "$GQL/diagnostics/cache" >/dev/null
}
backend_calls() {
    curl -s "$GQL/diagnostics/backend-calls" | python3 -c 'import json,sys;print(json.load(sys.stdin)["total"])'
}
has_errors() {   # reads stdin, prints "yes"/"no"
    python3 -c 'import json,sys;print("yes" if json.load(sys.stdin).get("errors") else "no")'
}
jpath() {        # jpath <json> <python expression over d>
    printf '%s' "$1" | python3 -c "import json,sys;d=json.load(sys.stdin);print($2)" 2>/dev/null
}

if ! curl -sf -o /dev/null --max-time 3 "$GQL/graphql" -X POST \
        -H 'Content-Type: application/json' -d '{"query":"{__typename}"}'; then
    echo "GraphQL facade is not answering on $GQL"
    echo "Start it:  cd phase6-graphql && ./mvnw spring-boot:run"
    exit 1
fi

# ---------------------------------------------------------------------------
banner "1. The schema is the contract, and it is self-describing"
TYPES=$(gql '{ __schema { queryType { name } mutationType { name } } }')
[ "$(jpath "$TYPES" 'd["data"]["__schema"]["queryType"]["name"]')" = "Query" ] \
    && pass "schema introspects" || fail "introspection failed"

# The FULL introspection query, which is what GraphiQL actually sends. The
# shallow one above passed while the schema browser was broken by the depth
# limit, so asserting the real thing is the only version that means anything.
FULL_INTROSPECTION='query IntrospectionQuery { __schema { queryType { name } types { ...FullType } } } fragment FullType on __Type { kind name fields(includeDeprecated:true) { name args { ...InputValue } type { ...TypeRef } isDeprecated } inputFields { ...InputValue } interfaces { ...TypeRef } enumValues(includeDeprecated:true) { name } possibleTypes { ...TypeRef } } fragment InputValue on __InputValue { name type { ...TypeRef } defaultValue } fragment TypeRef on __Type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } } }'
DEEP=$(gql "$FULL_INTROSPECTION")
[ "$(printf '%s' "$DEEP" | has_errors)" = "no" ] \
    && pass "the full introspection query is not blocked by the query limits" \
    || fail "introspection is blocked: $(jpath "$DEEP" 'd["errors"][0]["message"][:60]')"
echo "  It is 15 levels deep. A depth limit of 10 rejected it and GraphiQL"
echo "  loaded with no docs and no autocomplete - green suite, broken UI."

DEPRECATED=$(gql '{ __type(name:"LowStockItem") { fields(includeDeprecated:true) { name isDeprecated } } }')
[ "$(jpath "$DEPRECATED" 'sum(1 for f in d["data"]["__type"]["fields"] if f["isDeprecated"])')" = "1" ] \
    && pass "suggestedOrderQty is published as deprecated, and still served" \
    || fail "the deprecated field is missing or not marked"
echo "  No version number anywhere. Deprecation plus usage tracking replaces it."

# ---------------------------------------------------------------------------
banner "2. Validation happens before any resolver runs"
ENUM=$(gql '{ lowStock(warehouse: WH_NYC) { sku } }')
[ "$(printf '%s' "$ENUM" | has_errors)" = "yes" ] \
    && pass "WH_NYC is rejected by the schema - not a valid enum value" \
    || fail "an invalid warehouse was accepted"
echo "  Phase 5 found that WH-NYC returns 200 [] from the REST facade, which a"
echo "  model read as 'no stock'. Here the value set is IN the contract, so the"
echo "  same mistake cannot be made - it is a query error, not a silent answer."

NULLABLE=$(gql '{ product(sku:"NOPE-XXX-999") { sku } }')
[ "$(jpath "$NULLABLE" 'd["data"]["product"] is None')" = "True" ] \
    && pass "an unknown SKU is null, not an error - absence is a valid answer" \
    || fail "unknown SKU did not resolve to null"

# ---------------------------------------------------------------------------
banner "3. THE N+1, MEASURED - and why the gateway cannot see it"
reset_counters
gql '{ lowStock { sku quantity } }' >/dev/null
CHEAP=$(backend_calls)
[ "$CHEAP" = "1" ] && pass "cheap query costs $CHEAP backend call" \
                   || fail "expected 1 backend call, got $CHEAP"

reset_counters
gql '{ lowStock { sku quantity product { name reorderQuantity } } }' >/dev/null
EXPENSIVE=$(backend_calls)
[ "$EXPENSIVE" -gt "$CHEAP" ] \
    && pass "adding one nested field costs $EXPENSIVE backend calls" \
    || fail "expected the nested query to cost more than $CHEAP, got $EXPENSIVE"

echo "  Both are ONE HTTP POST to ONE URL. Kong counted 1 against the rate"
echo "  limit for each, because counting requests is all a gateway can do"
echo "  without parsing the query body - which is the application's job."
echo "  ${CHEAP} call vs ${EXPENSIVE} calls, same quota. That is the whole problem."

# ---------------------------------------------------------------------------
banner "4. Cost analysis - the limit that moved into the server"
reset_counters
TOOCOSTLY=$(gql '{ lowStock { sku product { name stockLevels { warehouseCode quantity } } } }')
[ "$(printf '%s' "$TOOCOSTLY" | has_errors)" = "yes" ] \
    && pass "a query over the complexity budget is refused" \
    || fail "an over-budget query was executed"
printf '  %s\n' "$(jpath "$TOOCOSTLY" 'd["errors"][0]["message"][:78]')"

REFUSED_COST=$(backend_calls)
[ "$REFUSED_COST" = "0" ] \
    && pass "the refused query cost 0 backend calls - rejected before execution" \
    || fail "a refused query still made $REFUSED_COST backend calls"
echo "  Not killed partway through. Complexity is computed after validation and"
echo "  before execution, so the SOAP service never hears about it."

# ---------------------------------------------------------------------------
banner "5. The write, and the idempotency key that became a schema field"
IDEM="gql-test-$$"
MUT="mutation { recordMovement(input:{sku:\"ELEC-LAP-001\", warehouseCode:WH_EAST, movementType:ADJUSTMENT, quantity:1, idempotencyKey:\"$IDEM\"}) { movementId quantityBefore quantityAfter replayed } }"
FIRST=$(gql "$MUT")
SECOND=$(gql "$MUT")

[ "$(jpath "$FIRST" 'd["data"]["recordMovement"]["replayed"]')" = "False" ] \
    && pass "the first call records the movement" || fail "first call did not record"
[ "$(jpath "$SECOND" 'd["data"]["recordMovement"]["replayed"]')" = "True" ] \
    && pass "the second call replays it instead of writing twice" \
    || fail "a repeated mutation wrote a second movement"
[ "$(jpath "$FIRST" 'd["data"]["recordMovement"]["movementId"]')" \
  = "$(jpath "$SECOND" 'd["data"]["recordMovement"]["movementId"]')" ] \
    && pass "both calls returned the same movementId" || fail "movementIds differ"
echo "  In Phase 2 this was an HTTP header. GraphQL has no header convention to"
echo "  borrow, so transport metadata had to become part of the schema."

# ---------------------------------------------------------------------------
banner "6. Through the gateway"
if ! curl -sf -o /dev/null --max-time 2 "$PROXY/graphql" -X POST \
        -H "apikey: $KEY" -H 'Content-Type: application/json' \
        -d '{"query":"{__typename}"}' 2>/dev/null; then
    skip "Kong is not routing /graphql yet"
    echo "  Add the route from phase6-graphql/README.md to phase3-gateway/kong.yml,"
    echo "  then: docker compose restart kong"
else
    pass "GraphQL is reachable through Kong with an api key"
    UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PROXY/graphql" \
             -H 'Content-Type: application/json' -d '{"query":"{__typename}"}')
    [ "$UNAUTH" = "401" ] \
        && pass "and rejected without one - auth still belongs to the gateway" \
        || fail "expected 401 without an api key, got $UNAUTH"
    echo "  Kong still does auth, coarse throttling and the correlation ID."
    echo "  It just cannot tell a 1-call query from a 7-call one."
fi

# ---------------------------------------------------------------------------
banner "Result"
[ "$FAILED" = "0" ] && printf '  \033[32mall checks passed\033[0m\n\n' \
                    || printf '  \033[31msomething failed\033[0m\n\n'
exit $FAILED
