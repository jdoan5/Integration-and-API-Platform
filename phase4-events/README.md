# Phase 4 — Kafka, Avro & Schema Registry

Domain events published from the inventory system using the **transactional
outbox** pattern, serialized with **Avro**, governed by a **Schema Registry**.

- **App:** [`app/`](app/) — outbox relay + two consumers, port 8083
- **Kafka:** `localhost:9092` · **Schema Registry:** `localhost:8085`
- **Event contract:** [`StockMovementRecorded.avsc`](app/src/main/resources/avro/StockMovementRecorded.avsc)

## Run it

```bash
docker compose up -d kafka schema-registry
```

Apply the two SQL files to `inventory_mgmt`, then start the app:

```bash
cd phase4-events/app && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

Watch the pipeline:

```bash
curl -s http://localhost:8083/events/status | python3 -m json.tool
```

## Why an outbox instead of just calling Kafka

The obvious approach is a **dual write**:

```java
repo.insertMovement(...);      // (1) database
kafkaProducer.send(event);     // (2) broker
```

Two systems, no shared transaction, and two of the four outcomes are bugs: the
process can die after (1) and **lose the event forever**, or the transaction can
roll back after (2) and **announce something that never happened**. Wrapping it
in try/catch only moves the window.

So Phase 1 writes the event into `event_outbox` **inside the same transaction**
as the movement — one atomic commit. This service's relay then reads that table
and publishes to Kafka.

The relay can crash between sending and marking a row published, so delivery is
**at-least-once**. That is the honest guarantee, not a defect: exactly-once
across two independent systems is generally unachievable, while at-least-once
plus idempotent consumers is. Both consumers here deduplicate.

**Verified:** replaying every outbox row left the read-model totals byte-for-byte
identical, `duplicatesIgnored` rose to 3, and no duplicate alert fired.

## The two consumers

| Consumer | Purpose | How it deduplicates |
|---|---|---|
| `LowStockAlerter` | Warns when a movement crosses below the reorder point | Bounded in-memory LRU of movement ids |
| `MovementProjector` | Builds a daily-totals read model (CQRS) | `ON CONFLICT` + a `movement_ids` array, in SQL |

The projector's approach is stronger — it survives restarts and is shared across
instances. The alerter's in-memory set is honest for a demo but would need to
move to Redis or a table in production. **Something** must deduplicate.

Note the alerter fires only on the **transition** into low stock, not on every
movement while already low. Otherwise a busy SKU pages you on every shipment.

## The schema evolution exercise — do this one

```bash
./phase4-events/schema-evolution-demo.sh
```

This is the most valuable exercise in the whole project. Real output:

| Change | Verdict |
|---|---|
| Add optional field with a default | ✅ **Accepted** — old data decodes via the default |
| Add **required** field (no default) | ❌ **Rejected** — existing events lack it, nothing to fall back on |
| Remove a required field | ✅ **Accepted** under BACKWARD — a new reader just ignores it |

That third result surprises people, and it is the point. **BACKWARD** asks only
"can a new reader read old data?" A consumer still on the *old* schema reading
*new* data is a different question — **FORWARD** compatibility. **FULL** requires
both. Choosing a policy means deciding who is allowed to upgrade first.

### The comparison to Phase 1

| XSD (Phase 1) | Avro + Registry (Phase 4) |
|---|---|
| Contract lives in your repo | Contract lives in a shared registry |
| Validates one **message** | Validates the **schema change itself** |
| Nothing stops a breaking edit | Registry **refuses** incompatible changes |
| Consumers find out in production | Producer finds out at deploy time |

Both are contract-first. Only one has a referee. Being able to explain *that* —
rather than "I used Kafka" — is what makes this material interview-ready.

## Details worth noticing

- **The message key is the SKU.** Kafka preserves order within a partition and
  the same key always hashes to the same partition, so all events for one
  product stay ordered even though the topic as a whole does not.
- **`acks=all` + `enable.idempotence=true`** on the producer. With `acks=1` a
  broker crash right after the ack loses the event, defeating the outbox.
- **The relay sends synchronously** (`.get()`) and only marks a row published
  after the broker acks. Fire-and-forget would reintroduce the lost-event bug.
- **`SPECIFIC_AVRO_READER_CONFIG=true`** is the setting people miss. Without it
  you get a `GenericRecord` and lose compile-time field checking.
- **The deserializer never needs the schema.** Each message carries a 5-byte
  header with the schema id; the consumer fetches that exact writer schema from
  the registry and projects it onto its own.
- **A partial index** (`WHERE published_at IS NULL`) keeps the relay's hot query
  fast without growing alongside published history.

## Exercises

1. **Rebuild the read model from scratch.** Truncate `movement_daily_totals`,
   reset the consumer group offset to earliest, and watch it repopulate from the
   topic. The read model is a cache of the log, not a second source of truth.
2. **Break the registry's rules deliberately.** Switch the subject to `FORWARD`
   and re-run the demo — the accepted and rejected cases swap around.
3. **Make the alerter's dedup durable.** Move the in-memory set into Redis or a
   `processed_events` table and prove it survives a restart.
4. **Add a second topic.** Emit `inventory.reorder-needed.v1` from the alerter so
   another service can act on it, and notice you now have a chain of events.
