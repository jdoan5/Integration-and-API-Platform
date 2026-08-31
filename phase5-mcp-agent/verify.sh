#!/usr/bin/env bash
# ============================================================================
# verify.sh  -  prove the MCP layer works, with and without the platform up.
#
#   ./verify.sh
#
# Sections 1-3 need NOTHING running: no gateway, no database, no API key.
# Section 4 is skipped unless Kong is on :8000 with the REST facade behind it.
#
# That split is the point. The contract, the annotations and the guards are
# properties of this phase and are testable on their own; only the data needs
# the rest of the stack.
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")"
PY=./.venv/bin/python
PROXY=${MCP_BASE_URL:-http://localhost:8000}
KEY=${MCP_API_KEY:-local-demo-key-internal}

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$1"; }
banner() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
FAILED=0

if [ ! -x "$PY" ]; then
    echo "No virtualenv. Run:  python3.14 -m venv .venv && ./.venv/bin/pip install -e '.[agent,dev]'"
    exit 1
fi

# ---------------------------------------------------------------------------
banner "1. The MCP contract - what a model actually sees"
CONTRACT=$($PY - <<'PYEOF'
import asyncio, json
from inventory_mcp.server import mcp

async def main():
    tools = await mcp.list_tools()
    write = next(t for t in tools if t.name == "record_movement")
    print(json.dumps({
        "tools": len(tools),
        "resources": len(await mcp.list_resources()),
        "prompts": len(await mcp.list_prompts()),
        "reads_flagged_readonly": sum(
            1 for t in tools if t.annotations and t.annotations.readOnlyHint),
        "write_is_destructive": bool(write.annotations.destructiveHint),
        "write_leaks_ctx": "ctx" in write.inputSchema.get("properties", {}),
    }))

asyncio.run(main())
PYEOF
)
get() { echo "$CONTRACT" | $PY -c "import json,sys;print(json.load(sys.stdin)['$1'])"; }

[ "$(get tools)" = "6" ]     && pass "6 tools published"     || fail "expected 6 tools, got $(get tools)"
[ "$(get resources)" = "3" ] && pass "3 resources published" || fail "expected 3 resources"
[ "$(get prompts)" = "2" ]   && pass "2 prompts published"   || fail "expected 2 prompts"
[ "$(get reads_flagged_readonly)" = "5" ] && pass "every read is annotated readOnlyHint" \
    || fail "a read tool is missing its readOnly annotation"
[ "$(get write_is_destructive)" = "True" ] && pass "record_movement is annotated destructive" \
    || fail "the write is not flagged destructive"
[ "$(get write_leaks_ctx)" = "False" ] && pass "Context is injected, never asked of the model" \
    || fail "ctx leaked into the tool schema"

# ---------------------------------------------------------------------------
banner "2. The guards that exist because the consumer improvises"
$PY -m pytest tests/ -q >/tmp/mcp-verify-pytest.txt 2>&1
if [ $? -eq 0 ]; then
    pass "$(tail -1 /tmp/mcp-verify-pytest.txt | tr -d '\n')"
else
    fail "offline suite failed - see /tmp/mcp-verify-pytest.txt"
fi
echo "  Covers: idempotency-key derivation, closed-set validation, 401/429"
echo "  translation, the approval interrupt, and the call budget."

# ---------------------------------------------------------------------------
banner "3. The agent loads the tools over a real stdio transport"
TOOLS=$($PY - <<'PYEOF' 2>/dev/null
import asyncio
from agent.graph import build_agent
async def main():
    b = await build_agent()
    print(f"{len(b.tools)} {b.provider}")
asyncio.run(main())
PYEOF
)
if [ -n "$TOOLS" ]; then
    pass "agent spawned the server and loaded ${TOOLS%% *} tools (model: ${TOOLS##* })"
    echo "  Same transport Claude Desktop uses - not a special path built for the agent."
else
    fail "agent could not load tools over stdio"
fi

# ---------------------------------------------------------------------------
banner "4. Against the live platform"
if ! curl -sf -o /dev/null --max-time 2 "$PROXY/api/v1/products/ELEC-LAP-001" -H "apikey: $KEY"; then
    skip "gateway not reachable at $PROXY"
    echo "  Start it:  docker compose up -d redis kong"
    echo "             cd ../phase2-rest-facade && ./mvnw spring-boot:run"
else
    pass "gateway reachable with an api key"

    # The trace must start at the agent: Kong only generates a correlation ID
    # when the caller did not send one.
    CID="mcp-verify-$$"
    ECHOED=$(curl -s -D- -o /dev/null -H "apikey: $KEY" -H "X-Correlation-ID: $CID" \
             "$PROXY/api/v1/products/ELEC-LAP-001" | tr -d '\r' \
             | grep -i '^X-Correlation-ID:' | cut -d' ' -f2-)
    [ "$ECHOED" = "$CID" ] && pass "our correlation ID survives the gateway (not replaced)" \
        || fail "correlation ID changed: sent $CID, got '$ECHOED'"

    # The behaviour that makes a write tool safe to hand to a model.
    IDEM="verify-$$"
    BODY='{"sku":"ELEC-LAP-001","warehouseCode":"WH-EAST","movementType":"ADJUSTMENT","quantity":1}'
    R1=$(curl -s -X POST "$PROXY/api/v1/movements" -H "apikey: $KEY" \
         -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM" -d "$BODY")
    R2=$(curl -s -X POST "$PROXY/api/v1/movements" -H "apikey: $KEY" \
         -H 'Content-Type: application/json' -H "Idempotency-Key: $IDEM" -d "$BODY")
    echo "$R2" | grep -q '"replayed":true' \
        && pass "a replayed write returns the first result instead of moving stock twice" \
        || fail "replay was not detected - a model retry would double-count stock"

    echo "  Now ask the agent something:"
    echo "    ./.venv/bin/python -m agent.cli 'what needs restocking?'"
fi

# ---------------------------------------------------------------------------
banner "Result"
[ "$FAILED" = "0" ] && printf '  \033[32mall checks passed\033[0m\n\n' \
                    || printf '  \033[31msomething failed\033[0m\n\n'
exit $FAILED
