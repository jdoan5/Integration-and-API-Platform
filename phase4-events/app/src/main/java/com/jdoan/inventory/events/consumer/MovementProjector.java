package com.jdoan.inventory.events.consumer;

import com.jdoan.inventory.events.avro.StockMovementRecorded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CONSUMER 2  -  maintains a denormalised READ MODEL.
 *
 * This is CQRS in miniature: the write side (Phase 1) stays normalised and
 * transactional, while this consumer builds a table shaped purely for reading -
 * daily movement totals per SKU and warehouse.
 *
 * Why bother? Answering "how much moved yesterday?" from stock_movements means
 * scanning and aggregating every time. Here it is one indexed lookup, and the
 * cost is paid once, asynchronously, as events arrive.
 *
 * The trade-off is EVENTUAL CONSISTENCY: for a moment after a write, this table
 * disagrees with stock_movements. That is acceptable for reporting and
 * unacceptable for, say, deciding whether stock exists to sell.
 *
 * IDEMPOTENCY: the ON CONFLICT clause makes reprocessing safe at the SQL level,
 * so a redelivered event cannot double-count. This is stronger than the
 * in-memory set in LowStockAlerter because it survives restarts.
 */
@Component
public class MovementProjector {

    private static final Logger log = LoggerFactory.getLogger(MovementProjector.class);

    private final JdbcClient db;
    private final AtomicLong applied = new AtomicLong();

    public MovementProjector(JdbcClient db) {
        this.db = db;
    }

    @KafkaListener(
            topics = "inventory.stock-movement.v1",
            groupId = "movement-projector",
            containerFactory = "avroListenerContainerFactory")
    public void onMovement(StockMovementRecorded event) {
        LocalDate day = event.getOccurredAt().atZone(ZoneId.systemDefault()).toLocalDate();
        boolean inbound = switch (event.getMovementType()) {
            case IN, RETURN, TRANSFER_IN -> true;
            default -> false;
        };

        // Deduplication happens in the database: the movement id set makes a
        // replay a no-op rather than a double count.
        int rows = db.sql("""
                    INSERT INTO movement_daily_totals
                        (movement_date, sku, warehouse_code, units_in, units_out, movement_ids)
                    VALUES (:day, :sku, :wh, :in, :out, ARRAY[:mid]::bigint[])
                    ON CONFLICT (movement_date, sku, warehouse_code) DO UPDATE
                    SET units_in  = movement_daily_totals.units_in
                                  + CASE WHEN :mid = ANY(movement_daily_totals.movement_ids) THEN 0 ELSE :in  END,
                        units_out = movement_daily_totals.units_out
                                  + CASE WHEN :mid = ANY(movement_daily_totals.movement_ids) THEN 0 ELSE :out END,
                        movement_ids = CASE
                            WHEN :mid = ANY(movement_daily_totals.movement_ids)
                            THEN movement_daily_totals.movement_ids
                            ELSE movement_daily_totals.movement_ids || :mid::bigint END
                """)
                .param("day", day)
                .param("sku", event.getSku())
                .param("wh", event.getWarehouseCode())
                .param("in",  inbound ? event.getQuantity() : 0)
                .param("out", inbound ? 0 : event.getQuantity())
                .param("mid", event.getMovementId())
                .update();

        applied.incrementAndGet();
        log.debug("projected movement {} into daily totals ({} row)", event.getMovementId(), rows);
    }

    public long appliedCount() { return applied.get(); }
}
