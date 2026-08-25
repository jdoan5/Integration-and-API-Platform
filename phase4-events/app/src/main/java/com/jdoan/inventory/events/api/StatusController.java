package com.jdoan.inventory.events.api;

import com.jdoan.inventory.events.consumer.LowStockAlerter;
import com.jdoan.inventory.events.consumer.MovementProjector;
import com.jdoan.inventory.events.relay.OutboxRelay;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lets you SEE the pipeline working rather than inferring it from logs. */
@RestController
@RequestMapping("/events")
public class StatusController {

    private final OutboxRelay relay;
    private final LowStockAlerter alerter;
    private final MovementProjector projector;
    private final JdbcClient db;

    public StatusController(OutboxRelay relay, LowStockAlerter alerter,
                            MovementProjector projector, JdbcClient db) {
        this.relay = relay;
        this.alerter = alerter;
        this.projector = projector;
        this.db = db;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> outbox = db.sql("""
                    SELECT COUNT(*) FILTER (WHERE published_at IS NULL)     AS pending,
                           COUNT(*) FILTER (WHERE published_at IS NOT NULL) AS published,
                           COALESCE(MAX(EXTRACT(EPOCH FROM (published_at - created_at))), 0) AS worst_lag_seconds
                    FROM event_outbox
                """).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pending", rs.getLong("pending"));
                    m.put("published", rs.getLong("published"));
                    m.put("worstLagSeconds", rs.getDouble("worst_lag_seconds"));
                    return m;
                }).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outbox", outbox);
        out.put("relay", Map.of("published", relay.publishedCount(), "failed", relay.failedCount()));
        out.put("lowStockAlerter", alerter.stats());
        out.put("movementProjector", Map.of("applied", projector.appliedCount()));
        return out;
    }

    @GetMapping("/daily-totals")
    public Object dailyTotals() {
        return db.sql("""
                    SELECT movement_date, sku, warehouse_code, units_in, units_out,
                           units_in - units_out AS net, array_length(movement_ids,1) AS events
                    FROM movement_daily_totals
                    ORDER BY movement_date DESC, sku
                    LIMIT 50
                """).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", rs.getString("movement_date"));
                    m.put("sku", rs.getString("sku"));
                    m.put("warehouse", rs.getString("warehouse_code"));
                    m.put("unitsIn", rs.getInt("units_in"));
                    m.put("unitsOut", rs.getInt("units_out"));
                    m.put("net", rs.getInt("net"));
                    m.put("events", rs.getInt("events"));
                    return m;
                }).list();
    }
}
