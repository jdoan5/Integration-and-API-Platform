#!/usr/bin/env bash
# ============================================================================
# schema-evolution-demo.sh
#
# THE MOST IMPORTANT EXERCISE IN THIS PROJECT.
#
# Phase 1 taught contract-first with an XSD. This shows what an XSD cannot do:
# have a runtime REFUSE your breaking change before it reaches consumers.
#
#   ./schema-evolution-demo.sh
# ============================================================================
set -uo pipefail

SR=http://localhost:8085
SUBJECT=inventory.stock-movement.v1-value

head() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
ok()   { printf '  \033[32m%s\033[0m\n' "$1"; }
no()   { printf '  \033[31m%s\033[0m\n' "$1"; }
note() { printf '  %s\n' "$1"; }

# ---------------------------------------------------------------------------
head "1. What is registered right now"
echo "  subjects: $(curl -s $SR/subjects)"
echo "  versions: $(curl -s $SR/subjects/$SUBJECT/versions)"
echo "  compatibility policy: $(curl -s $SR/config | python3 -c 'import sys,json;print(json.load(sys.stdin)["compatibilityLevel"])')"
note ""
note "BACKWARD means: a consumer using the NEW schema must be able to read data"
note "written with the OLD one. It protects consumers that upgrade first."

# ---------------------------------------------------------------------------
head "2. A COMPATIBLE change: add an optional field with a default"
cat > /tmp/schema_v2_ok.json <<'JSON'
{"schema": "{\"type\":\"record\",\"name\":\"StockMovementRecorded\",\"namespace\":\"com.jdoan.inventory.events.avro\",\"fields\":[{\"name\":\"movementId\",\"type\":\"long\"},{\"name\":\"sku\",\"type\":\"string\"},{\"name\":\"warehouseCode\",\"type\":\"string\"},{\"name\":\"movementType\",\"type\":{\"type\":\"enum\",\"name\":\"MovementType\",\"symbols\":[\"IN\",\"OUT\",\"TRANSFER_IN\",\"TRANSFER_OUT\",\"ADJUSTMENT\",\"RETURN\"]}},{\"name\":\"quantity\",\"type\":\"int\"},{\"name\":\"quantityBefore\",\"type\":\"int\"},{\"name\":\"quantityAfter\",\"type\":\"int\"},{\"name\":\"referenceType\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"occurredAt\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"operatorId\",\"type\":[\"null\",\"string\"],\"default\":null}]}"}
JSON
note "adding:  operatorId : [null, string] = null"
R=$(curl -s -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
      --data @/tmp/schema_v2_ok.json "$SR/compatibility/subjects/$SUBJECT/versions/latest")
echo "  registry says: $R"
echo "$R" | grep -q '"is_compatible":true' \
  && ok "ACCEPTED - old data decodes because the reader falls back to the default" \
  || no "unexpectedly rejected"

# ---------------------------------------------------------------------------
head "3. A BREAKING change: add a REQUIRED field (no default)"
cat > /tmp/schema_v2_bad.json <<'JSON'
{"schema": "{\"type\":\"record\",\"name\":\"StockMovementRecorded\",\"namespace\":\"com.jdoan.inventory.events.avro\",\"fields\":[{\"name\":\"movementId\",\"type\":\"long\"},{\"name\":\"sku\",\"type\":\"string\"},{\"name\":\"warehouseCode\",\"type\":\"string\"},{\"name\":\"movementType\",\"type\":{\"type\":\"enum\",\"name\":\"MovementType\",\"symbols\":[\"IN\",\"OUT\",\"TRANSFER_IN\",\"TRANSFER_OUT\",\"ADJUSTMENT\",\"RETURN\"]}},{\"name\":\"quantity\",\"type\":\"int\"},{\"name\":\"quantityBefore\",\"type\":\"int\"},{\"name\":\"quantityAfter\",\"type\":\"int\"},{\"name\":\"referenceType\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"occurredAt\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}},{\"name\":\"warehouseZone\",\"type\":\"string\"}]}"}
JSON
note "adding:  warehouseZone : string   (REQUIRED, no default)"
R=$(curl -s -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
      --data @/tmp/schema_v2_bad.json "$SR/compatibility/subjects/$SUBJECT/versions/latest")
echo "  registry says: $(echo "$R" | cut -c1-200)"
echo "$R" | grep -q '"is_compatible":false' \
  && ok "REJECTED - and this is the whole point" \
  || no "unexpectedly accepted"
note ""
note "Every event already on the topic lacks warehouseZone. A consumer using"
note "this schema could not decode ANY of them, and there is no default to"
note "fall back to. The registry stops it before it ships."

# ---------------------------------------------------------------------------
head "4. Another breaking change: REMOVE a required field"
cat > /tmp/schema_v2_remove.json <<'JSON'
{"schema": "{\"type\":\"record\",\"name\":\"StockMovementRecorded\",\"namespace\":\"com.jdoan.inventory.events.avro\",\"fields\":[{\"name\":\"movementId\",\"type\":\"long\"},{\"name\":\"sku\",\"type\":\"string\"},{\"name\":\"warehouseCode\",\"type\":\"string\"},{\"name\":\"quantity\",\"type\":\"int\"},{\"name\":\"quantityBefore\",\"type\":\"int\"},{\"name\":\"quantityAfter\",\"type\":\"int\"},{\"name\":\"referenceType\",\"type\":[\"null\",\"string\"],\"default\":null},{\"name\":\"occurredAt\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}}]}"}
JSON
note "removing:  movementType   (a required field)"
R=$(curl -s -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
      --data @/tmp/schema_v2_remove.json "$SR/compatibility/subjects/$SUBJECT/versions/latest")
echo "  registry says: $(echo "$R" | cut -c1-160)"
echo "$R" | grep -q '"is_compatible":true' \
  && ok "ACCEPTED under BACKWARD - a new reader simply ignores the old field" \
  || no "REJECTED"
note ""
note "Surprising? Under BACKWARD, dropping a field is fine for the READER."
note "But a consumer still on the OLD schema reading NEW data would break -"
note "that is FORWARD compatibility, a different policy. FULL requires both."

# ---------------------------------------------------------------------------
head "5. The comparison that matters"
cat <<'TEXT'
  XSD (Phase 1)                       Avro + Schema Registry (Phase 4)
  ---------------------------------   ------------------------------------
  Contract lives in your repo         Contract lives in a shared registry
  Validates ONE message at a time     Validates the SCHEMA CHANGE ITSELF
  Nothing stops a breaking edit       Registry REFUSES incompatible changes
  Consumers discover breakage at      Producer discovers it at deploy time
    runtime, in production
  Versioning by namespace, by hand    Versioning tracked automatically

  Both are contract-first. Only one has a referee.

  This is the single most useful thing to be able to explain in an
  integration interview: not "I used Kafka", but "I know what a schema
  registry prevents, and why BACKWARD and FORWARD are different questions".
TEXT

printf '\n\033[1mDone.\033[0m Nothing was actually registered - all checks used the\n'
printf 'read-only /compatibility endpoint, so the live schema is untouched.\n'
