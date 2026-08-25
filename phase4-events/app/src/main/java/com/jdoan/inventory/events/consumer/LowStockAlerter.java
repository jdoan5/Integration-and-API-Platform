package com.jdoan.inventory.events.consumer;

import com.jdoan.inventory.events.avro.StockMovementRecorded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CONSUMER 1  -  alerts when a movement pushes stock below its reorder point.
 *
 * IDEMPOTENCY: the relay guarantees at-least-once delivery, so this listener
 * WILL occasionally see the same movementId twice. Without deduplication a
 * replay would fire duplicate alerts - the classic "we got paged five times
 * for one incident" failure.
 *
 * The seen-set here is a bounded in-memory LRU, which is honest for a demo but
 * not for production: it is lost on restart and not shared between instances.
 * A real deployment stores processed ids in the database or Redis. The point
 * is that SOMETHING must deduplicate, and it has to survive restarts.
 */
@Component
public class LowStockAlerter {

    private static final Logger log = LoggerFactory.getLogger(LowStockAlerter.class);
    private static final int MEMORY = 10_000;

    private final JdbcClient db;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong alerts = new AtomicLong();

    /** Bounded LRU of movement ids already handled. */
    private final Set<Long> seen = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(MEMORY + 1, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > MEMORY;
                }
            }));

    public LowStockAlerter(JdbcClient db) {
        this.db = db;
    }

    @KafkaListener(
            topics = "inventory.stock-movement.v1",
            groupId = "low-stock-alerter",
            containerFactory = "avroListenerContainerFactory")
    public void onMovement(StockMovementRecorded event) {
        if (!seen.add(event.getMovementId())) {
            duplicates.incrementAndGet();
            log.info("duplicate movementId={} ignored (at-least-once delivery)", event.getMovementId());
            return;
        }
        processed.incrementAndGet();

        Integer reorderPoint = db.sql("SELECT reorder_point FROM products WHERE sku = :sku")
                .param("sku", event.getSku())
                .query(Integer.class)
                .optional()
                .orElse(null);

        if (reorderPoint == null) return;

        // Alert only on the TRANSITION into low stock, not on every movement
        // while already low - otherwise a busy SKU alerts on every shipment.
        boolean wasOk  = event.getQuantityBefore() >= reorderPoint;
        boolean nowLow = event.getQuantityAfter()  <  reorderPoint;

        if (wasOk && nowLow) {
            alerts.incrementAndGet();
            log.warn("LOW STOCK ALERT  {} at {} fell to {} (reorder point {}) - order {} more",
                    event.getSku(), event.getWarehouseCode(),
                    event.getQuantityAfter(), reorderPoint,
                    reorderPoint - event.getQuantityAfter());
        }
    }

    public Map<String, Long> stats() {
        return Map.of("processed", processed.get(),
                      "duplicatesIgnored", duplicates.get(),
                      "alertsRaised", alerts.get());
    }
}
