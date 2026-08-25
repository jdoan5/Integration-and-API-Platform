-- ============================================================================
-- 02_read_model.sql  -  the CQRS read model built by MovementProjector.
--
-- This table is DERIVED. Nothing writes to it except the Kafka consumer, and
-- it can be rebuilt at any time by resetting the consumer group offset and
-- replaying the topic. That replayability is a real advantage of event-driven
-- design: the read model is a cache of the event log, not a second source of
-- truth you have to migrate.
-- ============================================================================

DROP TABLE IF EXISTS movement_daily_totals;

CREATE TABLE movement_daily_totals (
    movement_date  DATE         NOT NULL,
    sku            VARCHAR(40)  NOT NULL,
    warehouse_code VARCHAR(10)  NOT NULL,
    units_in       INT          NOT NULL DEFAULT 0,
    units_out      INT          NOT NULL DEFAULT 0,

    -- Which movements are already counted here. This is what makes the
    -- projection IDEMPOTENT: a redelivered event whose id is already in the
    -- array adds nothing. Without it, at-least-once delivery would inflate
    -- every total.
    --
    -- (An array works at this scale; a high-volume system would use a separate
    --  processed_events table with a primary key on the id.)
    movement_ids   BIGINT[]     NOT NULL DEFAULT '{}',

    PRIMARY KEY (movement_date, sku, warehouse_code)
);

CREATE INDEX idx_daily_totals_sku ON movement_daily_totals (sku, movement_date DESC);


-- ----------------------------------------------------------------------------
-- The query this table exists to make fast:
--
--   SELECT movement_date, sku, warehouse_code, units_in, units_out,
--          units_in - units_out AS net
--   FROM movement_daily_totals
--   ORDER BY movement_date DESC, sku;
--
-- Compare with computing the same thing from scratch every time:
--
--   SELECT DATE(sm.created_at), p.sku, w.code,
--          SUM(CASE WHEN sm.movement_type IN ('IN','RETURN','TRANSFER_IN')
--                   THEN sm.quantity ELSE 0 END),
--          SUM(CASE WHEN sm.movement_type IN ('OUT','TRANSFER_OUT')
--                   THEN sm.quantity ELSE 0 END)
--   FROM stock_movements sm
--   JOIN products p   ON p.product_id = sm.product_id
--   JOIN warehouses w ON w.warehouse_id = sm.warehouse_id
--   GROUP BY 1,2,3;
--
-- Both give the same answer. The second one re-reads the whole ledger.
-- ----------------------------------------------------------------------------
