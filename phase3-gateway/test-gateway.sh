#!/usr/bin/env bash
# ============================================================================
# test-gateway.sh  -  exercise every Kong behaviour this phase configures.
#
#   ./test-gateway.sh
#
# Requires: Kong on :8000, REST facade on :8082, SOAP service on :8081.
#
# NOTE ON RE-RUNS: section 5 deliberately burns through the rate limit, and
# Kong's window is a fixed 60 seconds. Running this suite twice inside the
# same minute can therefore make a later assertion fail on quota it did not
# spend itself. Wait ~60s between runs, or give each run its own consumer.
#
# This is a real property of testing rate-limited systems, not a flaw to
# paper over: tests that mutate shared, time-windowed state are not
# independent, and pretending otherwise produces flaky suites.
# ============================================================================
set -uo pipefail

PROXY=http://localhost:8000
ADMIN=http://localhost:8001
KEY=local-demo-key-mobile

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
head() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

check() { # check <description> <actual> <expected>
    if [ "$2" = "$3" ]; then pass "$1 ($2)"; else fail "$1 (got $2, expected $3)"; fi
}

# ---------------------------------------------------------------------------
head "1. Gateway is up and knows its config"
check "admin API reachable" \
      "$(curl -s -o /dev/null -w '%{http_code}' $ADMIN/status)" "200"
echo "  services: $(curl -s $ADMIN/services | grep -o '"name":"[^"]*"' | cut -d'"' -f4 | tr '\n' ' ')"
echo "  routes:   $(curl -s $ADMIN/routes   | grep -o '"name":"[^"]*"' | cut -d'"' -f4 | tr '\n' ' ')"

# ---------------------------------------------------------------------------
head "2. AUTHENTICATION - no key means no entry"
check "request WITHOUT an api key is rejected" \
      "$(curl -s -o /dev/null -w '%{http_code}' $PROXY/api/v1/products/ELEC-LAP-001)" "401"
check "request with a BOGUS key is rejected" \
      "$(curl -s -o /dev/null -w '%{http_code}' -H 'apikey: totally-wrong' $PROXY/api/v1/products/ELEC-LAP-001)" "401"
check "request with a VALID key succeeds" \
      "$(curl -s -o /dev/null -w '%{http_code}' -H "apikey: $KEY" $PROXY/api/v1/products/ELEC-LAP-001)" "200"
echo "  Your application code contains no auth logic at all - Kong did this."

# ---------------------------------------------------------------------------
head "3. RESPONSE TRANSFORMATION - headers added and stripped"
HDRS=$(curl -s -D- -o /dev/null -H "apikey: $KEY" $PROXY/api/v1/products/ELEC-LAP-001)
# Assert the exact VALUE, not just presence: Kong keeps whatever follows the
# colon, so a config of "X-Gateway: kong" silently yields " kong".
GW=$(echo "$HDRS" | tr -d '\r' | grep -i '^X-Gateway:' | cut -d' ' -f2-)
check "X-Gateway value is exactly 'kong'" "$GW" "kong"
echo "$HDRS" | grep -qi 'X-Served-By'   && pass "X-Served-By header injected" || fail "X-Served-By missing"
# Kong stamps its OWN Server/Via after plugins run; only KONG_HEADERS removes them.
echo "$HDRS" | tr -d '\r' | grep -qiE '^(Server|Via):' && fail "Kong version disclosed via Server/Via" || pass "no version disclosure (KONG_HEADERS)"

# ---------------------------------------------------------------------------
head "4. CORRELATION ID - one trace across every hop"
CID=$(curl -s -D- -o /dev/null -H "apikey: $KEY" $PROXY/api/v1/products/ELEC-LAP-001 \
      | grep -i 'X-Correlation-ID' | tr -d '\r' | awk '{print $2}')
[ -n "$CID" ] && pass "correlation id generated: $CID" || fail "no correlation id"

SUPPLIED="my-own-trace-id-42"
ECHOED=$(curl -s -D- -o /dev/null -H "apikey: $KEY" -H "X-Correlation-ID: $SUPPLIED" \
         $PROXY/api/v1/products/ELEC-LAP-001 | grep -i 'X-Correlation-ID' | tr -d '\r' | awk '{print $2}')
check "a caller-supplied id is preserved" "$ECHOED" "$SUPPLIED"

# ---------------------------------------------------------------------------
head "5. RATE LIMITING - enforced at the edge, counters in Redis"
echo "  firing 25 requests against a 20/min limit..."
CODES=$(for i in $(seq 1 25); do
    curl -s -o /dev/null -w '%{http_code}\n' -H "apikey: $KEY" $PROXY/api/v1/products/ELEC-LAP-001
done | sort | uniq -c | tr '\n' ' ')
echo "  results: $CODES"
echo "$CODES" | grep -q '429' && pass "limit enforced (429s returned)" || fail "no 429 seen"

echo "  --- a DIFFERENT consumer has its own quota ---"
check "partner key still allowed" \
      "$(curl -s -o /dev/null -w '%{http_code}' -H 'apikey: local-demo-key-partner' $PROXY/api/v1/products/ELEC-LAP-001)" "200"
echo "  Kong rate-limits per CONSUMER, not per IP - that is why identity comes first."

# ---------------------------------------------------------------------------
head "6. LEGACY SOAP THROUGH THE SAME GATEWAY"
SOAP_BODY='<?xml version="1.0"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:inv="http://jdoan.com/inventory/v1"><soap:Body><inv:GetProductRequest><inv:sku>ELEC-LAP-001</inv:sku></inv:GetProductRequest></soap:Body></soap:Envelope>'
SOAP_CODE=$(curl -s -o /tmp/soap_via_kong.xml -w '%{http_code}' -X POST $PROXY/soap/ws \
    -H 'Content-Type: text/xml;charset=UTF-8' -H 'SOAPAction: ""' --data-binary "$SOAP_BODY")
check "SOAP call proxied by Kong" "$SOAP_CODE" "200"
grep -q 'UltraBook' /tmp/soap_via_kong.xml && pass "SOAP response body intact" || fail "unexpected SOAP body"
check "SOAP route needs NO api key (legacy consumers not broken)" \
      "$SOAP_CODE" "200"

# ---------------------------------------------------------------------------
head "7. OBSERVABILITY"
MET=$(curl -s $ADMIN/metrics | grep -c '^kong_')
[ "$MET" -gt 0 ] && pass "prometheus exposing $MET kong_* metric lines" || fail "no metrics"

echo
printf '\033[1mDone.\033[0m Kong config lives entirely in kong.yml - no admin clicking.\n'
