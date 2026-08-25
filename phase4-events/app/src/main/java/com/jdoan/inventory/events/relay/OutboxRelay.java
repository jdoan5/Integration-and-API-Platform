package com.jdoan.inventory.events.relay;

import com.jdoan.inventory.events.avro.MovementType;
import com.jdoan.inventory.events.avro.StockMovementRecorded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE OUTBOX RELAY.
 *
 * Polls event_outbox for unpublished rows, converts each JSON payload to Avro,
 * publishes it to Kafka, and marks the row published.
 *
 * WHY A SEPARATE PROCESS AT ALL:
 * The write path (Phase 1) commits the movement and its event in one database
 * transaction. It never talks to Kafka, so it cannot half-succeed. This relay
 * then moves events from the database to the broker, and because it can crash
 * between "send" and "mark published", delivery is AT-LEAST-ONCE.
 *
 * That is not a flaw to fix - it is the honest guarantee. Exactly-once across
 * two independent systems is not achievable in general; at-least-once plus
 * idempotent consumers is. Every consumer here deduplicates on movementId.
 *
 * ORDERING: the Kafka message key is the SKU. Kafka preserves order within a
 * partition and the same key always lands in the same partition, so all events
 * for one product stay in order even though the topic as a whole does not.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcClient db;
    private final KafkaTemplate<String, Object> kafka;
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed    = new AtomicLong();

    public OutboxRelay(JdbcClient db, KafkaTemplate<String, Object> kafka) {
        this.db = db;
        this.kafka = kafka;
    }

    record OutboxRow(long outboxId, String aggregateId, String topic, String payload) {}

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void relay() {
        List<OutboxRow> batch = fetchUnpublished();
        if (batch.isEmpty()) return;

        log.info("relaying {} outbox event(s)", batch.size());
        for (OutboxRow row : batch) {
            try {
                StockMovementRecorded event = toAvro(row.payload());

                // .get() makes the send synchronous: we only mark the row
                // published after the broker has ACKED it. Fire-and-forget here
                // would reintroduce the very lost-event bug the outbox prevents.
                kafka.send(row.topic(), event.getSku(), event).get();

                markPublished(row.outboxId());
                published.incrementAndGet();
                log.debug("published outbox #{} -> {} key={}", row.outboxId(), row.topic(), event.getSku());

            } catch (Exception e) {
                failed.incrementAndGet();
                recordFailure(row.outboxId(), e.getMessage());
                log.error("failed to publish outbox #{}: {}", row.outboxId(), e.getMessage());
                // Deliberately no rethrow: one poisoned row must not block the
                // rest of the batch. attempts/last_error make it visible, and a
                // real system would move it to a dead-letter table after N tries.
            }
        }
    }

    private List<OutboxRow> fetchUnpublished() {
        return db.sql("""
                    SELECT outbox_id, aggregate_id, topic, payload::text AS payload
                    FROM event_outbox
                    WHERE published_at IS NULL
                      AND attempts < 5
                    ORDER BY created_at
                    LIMIT :lim
                """)
                .param("lim", BATCH_SIZE)
                .query((rs, n) -> new OutboxRow(
                        rs.getLong("outbox_id"),
                        rs.getString("aggregate_id"),
                        rs.getString("topic"),
                        rs.getString("payload")))
                .list();
    }

    @Transactional
    public void markPublished(long outboxId) {
        db.sql("UPDATE event_outbox SET published_at = NOW() WHERE outbox_id = :id")
                .param("id", outboxId).update();
    }

    private void recordFailure(long outboxId, String error) {
        db.sql("UPDATE event_outbox SET attempts = attempts + 1, last_error = :err WHERE outbox_id = :id")
                .param("id", outboxId)
                .param("err", error == null ? "unknown" : error.substring(0, Math.min(500, error.length())))
                .update();
    }

    /** JSON in the outbox -> Avro on the wire. */
    private StockMovementRecorded toAvro(String payloadJson) {
        JsonNode n = json.readTree(payloadJson);
        return StockMovementRecorded.newBuilder()
                .setMovementId(n.get("movementId").asLong())
                .setSku(n.get("sku").asString())
                .setWarehouseCode(n.get("warehouseCode").asString())
                .setMovementType(MovementType.valueOf(n.get("movementType").asString()))
                .setQuantity(n.get("quantity").asInt())
                .setQuantityBefore(n.get("quantityBefore").asInt())
                .setQuantityAfter(n.get("quantityAfter").asInt())
                .setReferenceType(n.hasNonNull("referenceType") ? n.get("referenceType").asString() : null)
                .setOccurredAt(Instant.parse(n.get("occurredAt").asString()))
                .build();
    }

    public long publishedCount() { return published.get(); }
    public long failedCount()    { return failed.get(); }
}
