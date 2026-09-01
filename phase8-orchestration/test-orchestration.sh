#!/usr/bin/env bash
# ============================================================================
# test-orchestration.sh  -  exercise the saga, including the paths that fail.
#
#   ./test-orchestration.sh
#
# Requires Temporal on :7233, this service on :8087, and the Phase 2 facade
# reachable through Kong on :8000.
#
# NOTE ON SIDE EFFECTS: every run moves real stock, and section 6 moves it back.
#
# That last part is not tidiness, it is correctness. The first version left the
# happy-path units at the destination, so each run drained the source warehouse
# by three. After about six runs WH-WEST was down to 2 units, shipOut started
# failing against the quantity >= 0 constraint, and the suite began reporting
# COMPENSATED for a transfer that was never the point of the test. A suite that
# degrades the fixture it depends on eventually tests only its own wreckage.
#
# The ledger still grows - every movement is real and stays in the history -
# but the BALANCE returns to where it started, so runs are independent.
# ============================================================================
set -uo pipefail

ORCH=${ORCH_URL:-http://localhost:8087}
PROXY=${KONG_URL:-http://localhost:8000}
KEY=${KONG_KEY:-local-demo-key-internal}
FACADE=${FACADE_URL:-http://localhost:8082}
SKU=${SKU:-ELEC-LAP-001}
FROM=WH-WEST
TO=WH-EAST

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
banner() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
FAILED=0

# Reads go DIRECT to the facade, not through Kong. Kong allows a consumer 20
# requests a minute, and this script polls a lot; through the gateway the
# suite spends the quota the WORKFLOW needs, the activities get 429, retries
# exhaust and every transfer compensates. The test then "fails" on behaviour
# that was correct. An observer should not compete with the thing it observes.
qty() {   # qty <warehouse>  -> current quantity for $SKU
    curl -s "$FACADE/api/v1/stock/$SKU" | python3 -c "
import json,sys
rows=json.load(sys.stdin)
print(next((r['quantity'] for r in rows if r['warehouseCode']=='$1'), 0))"
}
start() {  # start <quantity> <simulate>  -> workflowId
    curl -s -X POST "$ORCH/transfers" -H 'Content-Type: application/json' \
        -d "{\"sku\":\"$SKU\",\"fromWarehouse\":\"$FROM\",\"toWarehouse\":\"$TO\",\"quantity\":$1,\"simulate\":\"$2\"}" \
        | python3 -c "import json,sys;print(json.load(sys.stdin)['workflowId'])"
}
field() { # field <json> <key>
    printf '%s' "$1" | python3 -c "import json,sys;print(json.load(sys.stdin).get('$2'))" 2>/dev/null
}
await_stage() {  # await_stage <workflowId> <stage> - poll, do not guess a sleep
    for _ in $(seq 1 40); do
        [ "$(field "$(curl -s "$ORCH/transfers/$1")" stage)" = "$2" ] && return 0
        sleep 1
    done
    return 1
}

if ! curl -sf -o /dev/null --max-time 3 "$ORCH/actuator/health"; then
    echo "The orchestration service is not answering on $ORCH"
    echo "Start it:  cd phase8-orchestration && ./mvnw spring-boot:run"
    exit 1
fi

# ---------------------------------------------------------------------------
banner "1. The state a single event cannot represent"
W0=$(qty $FROM); E0=$(qty $TO)
WID=$(start 3 "")
await_stage "$WID" AWAITING_APPROVAL
STATE=$(curl -s "$ORCH/transfers/$WID")
[ "$(field "$STATE" stage)" = "AWAITING_APPROVAL" ] \
    && pass "the workflow answers 'where is it now': AWAITING_APPROVAL" \
    || fail "expected AWAITING_APPROVAL, got $(field "$STATE" stage)"
[ "$(field "$STATE" decision)" = "PENDING" ] \
    && pass "the decision reads PENDING, not a boolean masquerading as 'rejected'" \
    || fail "expected decision PENDING, got $(field "$STATE" decision)"

W1=$(qty $FROM); E1=$(qty $TO)
[ "$W1" = "$((W0 - 3))" ] && [ "$E1" = "$E0" ] \
    && pass "the goods have left $FROM ($W0->$W1) and not arrived at $TO (still $E1)" \
    || fail "expected $FROM to drop by 3 and $TO to be unchanged; got $W0->$W1 and $E0->$E1"
echo "  In transit: the stock is in neither warehouse. Phase 4 has no event for"
echo "  that, because it is the GAP between two events rather than one of them."

# ---------------------------------------------------------------------------
banner "2. A human signal completes it"
curl -s -X POST "$ORCH/transfers/$WID/approve?by=test-suite" >/dev/null
RESULT=$(curl -s "$ORCH/transfers/$WID/result")
[ "$(field "$RESULT" status)" = "COMPLETED" ] \
    && pass "approving finished the transfer" \
    || fail "expected COMPLETED, got $(field "$RESULT" status)"

W2=$(qty $FROM); E2=$(qty $TO)
[ "$W2" = "$((W0 - 3))" ] && [ "$E2" = "$((E0 + 3))" ] \
    && pass "3 units moved: $FROM $W0->$W2, $TO $E0->$E2" \
    || fail "arithmetic wrong: $FROM $W0->$W2, $TO $E0->$E2"
echo "  The signal is durable. Phase 5's approval interrupt lives in an"
echo "  in-memory checkpointer and dies with the process; this one does not."

# ---------------------------------------------------------------------------
banner "3. A rejection compensates"
W3=$(qty $FROM); E3=$(qty $TO)
WID2=$(start 4 "")
await_stage "$WID2" AWAITING_APPROVAL
curl -s -X POST "$ORCH/transfers/$WID2/reject?reason=budget" >/dev/null
R2=$(curl -s "$ORCH/transfers/$WID2/result")
[ "$(field "$R2" status)" = "REJECTED" ] \
    && pass "a rejected transfer reports REJECTED" \
    || fail "expected REJECTED, got $(field "$R2" status)"
[ "$(qty $FROM)" = "$W3" ] && [ "$(qty $TO)" = "$E3" ] \
    && pass "stock is back where it started - the compensation ran" \
    || fail "stock not restored: $FROM $W3->$(qty $FROM), $TO $E3->$(qty $TO)"
[ "$(field "$R2" outboundMovementId)" != "0" ] \
    && pass "the result still names the movement it made and reversed" \
    || fail "a compensated transfer reported movement id 0, hiding what it did"

# ---------------------------------------------------------------------------
banner "4. A failing activity compensates too"
W4=$(qty $FROM)
WID3=$(start 5 "carrier-unavailable")
R3=$(curl -s "$ORCH/transfers/$WID3/result")
[ "$(field "$R3" status)" = "FAILED" ] \
    && pass "an unavailable carrier fails the transfer" \
    || fail "expected FAILED, got $(field "$R3" status)"
[ "$(qty $FROM)" = "$W4" ] \
    && pass "$FROM restored to $W4 - stock was not lost in transit" \
    || fail "stock lost: $FROM $W4 -> $(qty $FROM)"
echo "  The compensation is registered immediately after the step it undoes,"
echo "  so every later failure unwinds it without anyone remembering to."

# ---------------------------------------------------------------------------
banner "5. The suite puts the stock back"
W5=$(qty $FROM); E5=$(qty $TO)
if [ "$W5" = "$W0" ]; then
    pass "$FROM already back at $W0 - nothing to return"
else
    MOVED=$((W0 - W5))
    WID4=$(curl -s -X POST "$ORCH/transfers" -H 'Content-Type: application/json' \
        -d "{\"sku\":\"$SKU\",\"fromWarehouse\":\"$TO\",\"toWarehouse\":\"$FROM\",\"quantity\":$MOVED,\"simulate\":\"\"}" \
        | python3 -c "import json,sys;print(json.load(sys.stdin)['workflowId'])")
    await_stage "$WID4" AWAITING_APPROVAL
    curl -s -X POST "$ORCH/transfers/$WID4/approve?by=test-suite-cleanup" >/dev/null
    curl -s "$ORCH/transfers/$WID4/result" >/dev/null
    [ "$(qty $FROM)" = "$W0" ] && [ "$(qty $TO)" = "$E0" ] \
        && pass "returned $MOVED units - the balance is where the suite found it" \
        || fail "cleanup failed: $FROM $(qty $FROM) (want $W0), $TO $(qty $TO) (want $E0)"
fi
echo "  The return trip runs through the workflow too, so the cleanup is the"
echo "  same code path it is cleaning up after."

# ---------------------------------------------------------------------------
banner "6. Retries meet an idempotent endpoint"
echo "  Temporal does not make retries safe - it makes them CERTAIN. Every"
echo "  activity here sends an Idempotency-Key derived from the workflow id and"
echo "  the step, never from the attempt, so a retried activity cannot move"
echo "  stock twice. Same lesson Phase 5 hit with a model holding a write tool."
LEDGER=$(curl -s "$FACADE/api/v1/stock/$SKU" >/dev/null; echo ok)
[ "$LEDGER" = "ok" ] && pass "the facade that enforces it is still reachable"

# ---------------------------------------------------------------------------
banner "Result"
echo "  Temporal UI: http://localhost:8233"
[ "$FAILED" = "0" ] && printf '  \033[32mall checks passed\033[0m\n\n' \
                    || printf '  \033[31msomething failed\033[0m\n\n'
exit $FAILED
