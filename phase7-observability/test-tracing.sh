#!/usr/bin/env bash
# ============================================================================
# test-tracing.sh  -  prove one trace spans the whole platform.
#
#   ./test-tracing.sh
#
# Requires Jaeger on :16686 plus the SOAP (:8081), REST (:8082) and GraphQL
# (:8086) services. Kong on :8000 is optional.
#
# Phase 3 already carried a correlation ID across every hop, and that was worth
# having: it tells you WHICH request you are looking at. What it cannot tell you
# is what the request did, in what order, or where the time went. Everything
# below is about that difference.
#
# NOTE ON TIMING: Jaeger ingests asynchronously and the exporter batches, so
# every assertion here sleeps first. A trace that has not landed yet is
# indistinguishable from one that never will.
# ============================================================================
set -uo pipefail

JAEGER=${JAEGER_URL:-http://localhost:16686}
GQL=${GRAPHQL_URL:-http://localhost:8086}
REST=${REST_URL:-http://localhost:8082}
PROXY=${KONG_URL:-http://localhost:8000}
KEY=${KONG_KEY:-local-demo-key-internal}
SETTLE=${TRACE_SETTLE:-8}

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; }
banner() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
FAILED=0

# Pull the richest recent trace for a service and report on it.
# usage: analyse <service> <python-expression over `t`, `procs`, `svcs`, `ops`>
analyse() {
    curl -s "$JAEGER/api/traces?service=$1&limit=20" | python3 -c "
import json,sys
from collections import Counter
d = json.load(sys.stdin).get('data') or []
if not d:
    print('NO_TRACES'); raise SystemExit
t = max(d, key=lambda x: len(x['spans']))
procs = {k: v['serviceName'] for k, v in t['processes'].items()}
svcs = Counter(procs[s['processID']] for s in t['spans'])
ops = Counter(s['operationName'] for s in t['spans'])
print($2)
"
}

if ! curl -sf -o /dev/null --max-time 3 "$JAEGER/api/services"; then
    echo "Jaeger is not answering on $JAEGER"
    echo "Start it:  docker compose up -d jaeger"
    exit 1
fi

# ---------------------------------------------------------------------------
banner "1. The services are reporting"
SERVICES=$(curl -s "$JAEGER/api/services" | python3 -c "import json,sys;print(' '.join(json.load(sys.stdin)['data'] or []))")
echo "  $SERVICES"
for s in graphql-facade soap-inventory-service rest-facade; do
    case " $SERVICES " in
        *" $s "*) pass "$s is exporting spans" ;;
        *) fail "$s has never sent a span" ;;
    esac
done

# ---------------------------------------------------------------------------
banner "2. ONE trace across GraphQL -> SOAP"
curl -s -X DELETE "$GQL/diagnostics/cache" >/dev/null
curl -s -X POST "$GQL/graphql" -H 'Content-Type: application/json' \
     -d '{"query":"{ lowStock { sku quantity product { name reorderQuantity } } }"}' >/dev/null
sleep "$SETTLE"

GQL_SVCS=$(analyse graphql-facade "len(svcs)")
[ "$GQL_SVCS" = "2" ] \
    && pass "a single trace covers both services" \
    || fail "expected 2 services in one trace, got $GQL_SVCS"
echo "  Without propagation this reads '1': both services report healthily,"
echo "  with different trace ids and one span each, and nothing looks broken."

SOAP_SPANS=$(analyse graphql-facade "svcs.get('soap-inventory-service',0)")
[ "${SOAP_SPANS:-0}" -ge 5 ] \
    && pass "the N+1 appears as $SOAP_SPANS separate SOAP spans in one request" \
    || fail "expected 5+ SOAP spans in the fan-out, got ${SOAP_SPANS:-0}"
echo "  Phase 6's counter said '7 backend calls' and was right. This is the"
echo "  same fact with a shape: one parent, a repeating child, all sequential."

# ---------------------------------------------------------------------------
banner "3. The agent's tool call reaches the database, in one trace"
MCP_SVCS=$(analyse mcp-inventory-server "len(svcs)")
if [ "$MCP_SVCS" = "NO_TRACES" ]; then
    skip "the MCP server has not sent spans - run the agent first:"
    echo "    ./phase5-mcp-agent/.venv/bin/python -m agent.cli 'why is ELEC-LAP-001 low?'"
else
    [ "${MCP_SVCS:-0}" -ge 2 ] \
        && pass "an MCP tool call and the platform share one trace ($MCP_SVCS services)" \
        || fail "the MCP trace covers only $MCP_SVCS service"
    ROOT=$(analyse mcp-inventory-server "min(t['spans'], key=lambda s: s['startTime'])['operationName']")
    case "$ROOT" in
        mcp.tool*) pass "the trace is rooted at the tool the model chose: '$ROOT'" ;;
        *) fail "expected an mcp.tool root span, got '$ROOT'" ;;
    esac
    echo "  The root is the MCP server, NOT the agent - see the README on why"
    echo "  trace context cannot cross the stdio boundary today."
fi

# ---------------------------------------------------------------------------
banner "4. ONE trace across REST -> SOAP"
curl -s "$REST/api/v1/products/ELEC-AUD-001" >/dev/null
sleep "$SETTLE"
REST_SVCS=$(analyse rest-facade "len(svcs)")
# >= 2, not == 2. Once the MCP server was instrumented the richest rest-facade
# trace became the agent's, which spans three services - and an assertion that
# the number is exactly two would fail on the system getting MORE observable.
[ "${REST_SVCS:-0}" -ge 2 ] \
    && pass "the REST facade's trace reaches the SOAP service too ($REST_SVCS services)" \
    || fail "expected the REST trace to span 2+ services, got $REST_SVCS"

# ---------------------------------------------------------------------------
banner "5. The event path traces too, as its own trace"
# Generate one, rather than asserting on whatever happens to be in Jaeger:
# the relay polls on a schedule, so a stale window is full of empty poll spans
# and the assertion passes or fails on timing rather than behaviour.
curl -s -X POST "$REST/api/v1/movements" -H 'Content-Type: application/json' \
     -H "Idempotency-Key: trace-suite-$$" \
     -d '{"sku":"ELEC-LAP-001","warehouseCode":"WH-EAST","movementType":"ADJUSTMENT","quantity":1}' \
     >/dev/null 2>&1
sleep "$((SETTLE + 12))"   # outbox poll + Kafka round trip + export

EV_SPANS=$(analyse events "len(t['spans'])")
if [ "$EV_SPANS" = "NO_TRACES" ]; then
    skip "the events app has sent no spans (is it running on :8083?)"
else
    KINDS=$(analyse events "sum(1 for s in t['spans'] for tg in s['tags'] if tg['key']=='span.kind' and tg['value']=='consumer')")
    [ "${KINDS:-0}" -ge 2 ] \
        && pass "trace context crossed Kafka to both consumers ($KINDS consumer spans)" \
        || fail "expected 2 consumer spans in the event trace, got ${KINDS:-0}"
    echo "  A broker is not an HTTP hop: the producer writes traceparent into the"
    echo "  record headers and the consumer reads it back. This is a SECOND trace,"
    echo "  not a continuation - the outbox is a deliberate boundary, not a break."
fi

# ---------------------------------------------------------------------------
banner "6. The infrastructure shows up, unasked"
REDIS_OPS=$(analyse rest-facade "sum(v for k,v in ops.items() if k in ('get','set','evalsha','del'))")
[ "${REDIS_OPS:-0}" -ge 1 ] \
    && pass "$REDIS_OPS Redis operations instrumented without a line of code" \
    || fail "no Redis spans - the cache and rate limiter are invisible"
echo "  evalsha is the Lua rate limiter from Phase 2; get/set are cache-aside."
echo "  Nobody wrote these spans. That is the argument for a standard."

# ---------------------------------------------------------------------------
banner "7. Trace context survives the gateway"
if ! curl -sf -o /dev/null --max-time 2 "$PROXY/api/v1/products/ELEC-LAP-001" -H "apikey: $KEY"; then
    skip "Kong is not reachable"
else
    TP="00-$(printf '%032x' $((RANDOM * RANDOM * RANDOM)))-$(printf '%016x' $((RANDOM * RANDOM)))-01"
    OWN=$(echo "$TP" | cut -d- -f2)
    curl -s -H "apikey: $KEY" -H "traceparent: $TP" \
         "$PROXY/api/v1/products/ELEC-LAP-001" >/dev/null
    sleep "$SETTLE"
    FOUND=$(curl -s "$JAEGER/api/traces/$OWN" | python3 -c "
import json,sys
try: print(len(json.load(sys.stdin).get('data') or []))
except Exception: print(0)")
    [ "${FOUND:-0}" -ge 1 ] \
        && pass "a caller-supplied traceparent is adopted, not replaced" \
        || fail "the caller's trace id was discarded (looked for $OWN)"
    echo "  The trace can therefore start at the CLIENT - which is the only way"
    echo "  to see the hop you actually care about, the one before your code."
fi

# ---------------------------------------------------------------------------
banner "Result"
echo "  Jaeger UI: $JAEGER/search?service=graphql-facade"
[ "$FAILED" = "0" ] && printf '  \033[32mall checks passed\033[0m\n\n' \
                    || printf '  \033[31msomething failed\033[0m\n\n'
exit $FAILED
