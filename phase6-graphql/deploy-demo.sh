#!/usr/bin/env bash
# ============================================================================
# deploy-demo.sh  -  publish the read-only GraphQL demo to Cloud Run.
#
#   ./deploy-demo.sh              # deploy
#   ./deploy-demo.sh --dry-run    # print what it would do, change nothing
#
# This makes a PUBLIC url. Read what it exposes before running it:
#
#   - A snapshot of the inventory data (15 products, 45 stock rows), exported
#     from a local development database. No customer data, no credentials.
#   - The GraphQL schema, and GraphiQL for exploring it.
#   - No write path: the mutation is refused by DemoInventoryBackend.
#   - No database, no SOAP service, no Redis, no gateway - nothing to reach.
#
# COST: min-instances is 0, so it scales to zero and idles free. A cold start is
# roughly 6 seconds. The generous request cap below exists to bound a runaway
# bill, not because the demo needs it.
# ============================================================================
set -euo pipefail

SERVICE=${SERVICE:-inventory-graphql-demo}
REGION=${REGION:-us-central1}
PROJECT=${PROJECT:-$(gcloud config get-value project 2>/dev/null)}
DRY_RUN=${1:-}

cd "$(dirname "$0")"

if [ -z "$PROJECT" ]; then
    echo "No GCP project set. Run: gcloud config set project <id>"
    exit 1
fi

echo "service : $SERVICE"
echo "project : $PROJECT"
echo "region  : $REGION"
echo "source  : $(pwd)"
echo

# --- the guard rails, all deliberate ---------------------------------------
ARGS=(
    run deploy "$SERVICE"
    # --source, not a locally built image: `docker build` on an Apple Silicon
    # Mac produces an arm64 image and Cloud Run runs amd64, so a local build
    # deploys and then fails to start with an exec-format error. Cloud Build
    # sidesteps that by building on the target architecture.
    --source .
    # Non-interactive: this accepts creating the cloud-run-source-deploy
    # Artifact Registry repository on first run. Without it the command waits
    # on a prompt that never arrives in CI or a background shell.
    --quiet
    --project "$PROJECT"
    --region "$REGION"
    # A demo nobody can open is not a demo. There is no write path and no data
    # worth protecting, so unauthenticated is the correct setting here - and it
    # is a decision, not a default.
    --allow-unauthenticated
    --port 8080
    --memory 512Mi
    --cpu 1
    # Scale to zero: this is idle almost always, and idle should cost nothing.
    --min-instances 0
    # Bounded so a crawler cannot turn a portfolio link into a bill.
    --max-instances 3
    --concurrency 40
    --timeout 30s
    --set-env-vars "SPRING_PROFILES_ACTIVE=demo"
    --labels "project=integration-api-platform,phase=6"
)

if [ "$DRY_RUN" = "--dry-run" ]; then
    echo "DRY RUN - would execute:"
    printf '  gcloud'; printf ' %q' "${ARGS[@]}"; echo
    exit 0
fi

gcloud "${ARGS[@]}"

URL=$(gcloud run services describe "$SERVICE" --region "$REGION" --project "$PROJECT" \
        --format 'value(status.url)')
echo
echo "deployed: $URL"
echo "graphiql: $URL/graphiql?path=/graphql"
echo
echo "Smoke test:"
echo "  curl -s -X POST $URL/graphql -H 'Content-Type: application/json' \\"
echo "       -d '{\"query\":\"{ lowStock { sku deficit } }\"}'"
