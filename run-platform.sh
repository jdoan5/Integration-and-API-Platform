#!/usr/bin/env bash
# ============================================================================
# run-platform.sh  -  start the whole platform, in order, and wait for it.
#
#   ./run-platform.sh              # infrastructure + every service
#   ./run-platform.sh status       # what is up right now
#   ./run-platform.sh stop         # stop the services, leave the containers
#   ./run-platform.sh stop --all   # stop the containers too
#   ./run-platform.sh logs phase6  # tail one service's log
#
# WHY THIS EXISTS: eight phases means six containers, five JVM services and a
# Python virtualenv, and they have to come up in order - a facade that starts
# before the SOAP service it calls looks broken in a way that has nothing to do
# with the facade. Doing that by hand is where an evening goes.
#
# Requires: Docker, Java 21, PostgreSQL with the inventory_mgmt database.
# ============================================================================
set -uo pipefail

cd "$(dirname "$0")"
ROOT=$(pwd)
LOGS=${LOGS:-/tmp/iap}
mkdir -p "$LOGS"

# name : directory : port : health url
SERVICES=(
    "phase1-soap:phase1-soap-service:8081:http://localhost:8081/ws/inventory.wsdl"
    "phase2-rest:phase2-rest-facade:8082:http://localhost:8082/actuator/health"
    "phase4-events:phase4-events/app:8083:http://localhost:8083/events/status"
    "phase6-graphql:phase6-graphql:8086:http://localhost:8086/actuator/health"
    "phase8-orchestration:phase8-orchestration:8087:http://localhost:8087/actuator/health"
)

ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; }
info() { printf '  \033[33m·\033[0m %s\n' "$1"; }
banner() { printf '\n\033[1m%s\033[0m\n' "$1"; }

port_up() { lsof -ti:"$1" >/dev/null 2>&1; }

wait_for() {  # wait_for <url> <seconds>
    for _ in $(seq 1 "${2:-90}"); do
        curl -sf -o /dev/null --max-time 2 "$1" 2>/dev/null && return 0
        sleep 1
    done
    return 1
}

# ---------------------------------------------------------------------------
cmd_status() {
    banner "Infrastructure"
    if ! docker info >/dev/null 2>&1; then
        bad "Docker is not running"
    else
        for c in iap-redis iap-kong iap-kafka iap-schema-registry iap-jaeger iap-temporal; do
            if [ -n "$(docker ps -q -f name=^${c}$ 2>/dev/null)" ]; then ok "$c"; else bad "$c"; fi
        done
    fi

    banner "Services"
    for entry in "${SERVICES[@]}"; do
        IFS=: read -r name _ port _ <<< "$entry"
        if port_up "$port"; then ok "$name  :$port"; else bad "$name  :$port"; fi
    done

    banner "Database"
    local psql
    psql=$(ls /Applications/Postgres.app/Contents/Versions/*/bin/psql 2>/dev/null | tail -1)
    psql=${psql:-$(command -v psql)}
    if [ -n "$psql" ] && "$psql" -d inventory_mgmt -tAc 'SELECT 1' >/dev/null 2>&1; then
        ok "inventory_mgmt"
    else
        bad "inventory_mgmt  (start Postgres.app)"
    fi

    banner "UIs"
    echo "  Temporal   http://localhost:8233"
    echo "  Jaeger     http://localhost:16686"
    echo "  GraphiQL   http://localhost:8086/graphiql?path=/graphql"
    echo
}

cmd_stop() {
    banner "Stopping services"
    for entry in "${SERVICES[@]}"; do
        IFS=: read -r name _ port _ <<< "$entry"
        if port_up "$port"; then
            lsof -ti:"$port" | xargs kill 2>/dev/null
            ok "$name stopped"
        fi
    done
    # spring-boot:run forks a child; the port kill above leaves the wrapper.
    pkill -f 'spring-boot:run' 2>/dev/null

    if [ "${1:-}" = "--all" ]; then
        banner "Stopping containers"
        docker compose stop 2>&1 | grep -E 'Stopping|Stopped' | sed 's/^/  /'
    else
        info "containers left running (use 'stop --all' to stop them too)"
    fi
    echo
}

cmd_logs() {
    local which=${1:-}
    [ -z "$which" ] && { echo "usage: ./run-platform.sh logs <phase1-soap|phase2-rest|...>"; exit 1; }
    tail -f "$LOGS/$which.log"
}

cmd_start() {
    if ! docker info >/dev/null 2>&1; then
        bad "Docker is not running - start Docker Desktop first"
        exit 1
    fi
    if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
        JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null)
        export JAVA_HOME
    fi
    [ -z "${JAVA_HOME:-}" ] && { bad "Java 21 not found"; exit 1; }

    banner "Infrastructure"
    docker compose up -d redis kong kafka schema-registry jaeger temporal >/dev/null 2>&1
    ok "containers requested"
    # Schema Registry is the slowest and depends on Kafka, so it gates the rest.
    if wait_for http://localhost:8085/subjects 120; then ok "kafka + schema registry ready"
    else info "schema registry slow to start - phase 4 may lag"; fi
    wait_for http://localhost:16686/ 30 && ok "jaeger ready"

    banner "Services"
    # Started in order: the facades call the SOAP service, so a facade that
    # comes up first spends its first requests failing for no good reason.
    for entry in "${SERVICES[@]}"; do
        IFS=: read -r name dir port health <<< "$entry"
        if port_up "$port"; then
            info "$name already on :$port"
            continue
        fi
        ( cd "$ROOT/$dir" && nohup ./mvnw -q spring-boot:run > "$LOGS/$name.log" 2>&1 & )
        if wait_for "$health" 120; then
            ok "$name  :$port"
        else
            bad "$name failed to start - see $LOGS/$name.log"
        fi
    done

    banner "Phase 5 (MCP agent) is on demand, not a server"
    echo "  cd phase5-mcp-agent && ./.venv/bin/python -m agent.cli 'what needs restocking?'"

    cmd_status
    banner "Verify"
    echo "  ./phase3-gateway/test-gateway.sh          15 assertions"
    echo "  ./phase5-mcp-agent/verify.sh              16"
    echo "  ./phase6-graphql/test-graphql.sh          14"
    echo "  ./phase7-observability/test-tracing.sh    11"
    echo "  ./phase8-orchestration/test-orchestration.sh  12"
    echo
}

case "${1:-start}" in
    start)  cmd_start ;;
    status) cmd_status ;;
    stop)   cmd_stop "${2:-}" ;;
    logs)   cmd_logs "${2:-}" ;;
    *)      echo "usage: ./run-platform.sh [start|status|stop [--all]|logs <service>]"; exit 1 ;;
esac
