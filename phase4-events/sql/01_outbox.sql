-- ============================================================================
-- 01_outbox.sql  -  the TRANSACTIONAL OUTBOX table.
-- Run against inventory_mgmt (the database from the SQL project).
--
-- THE PROBLEM THIS SOLVES
-- -----------------------
-- The naive way to publish domain events is a "dual write":
--
--     INSERT INTO stock_movements ...   -- (1) database
--     kafkaProducer.send(event)         -- (2) message broker
--
-- Those are two separate systems with no shared transaction. Four things can
-- happen, and two of them are bugs:
--
--     (1) ok, (2) ok      -> correct
--     (1) fails           -> correct (nothing happened)
--     (1) ok, (2) fails   -> STOCK MOVED BUT NOBODY WAS TOLD    <-- lost event
--     (1) ok, (2) ok,
--         then tx rolls back -> EVENT ANNOUNCED THAT NEVER HAPPENED <-- phantom
--
-- Retrying (2) does not fix it: the process can crash between the two, and
-- wrapping them in a try/catch just moves the window.
--
-- THE FIX
-- -------
-- Write the event into THIS TABLE inside the SAME database transaction as the
-- business change. Now it is one atomic commit - either both the movement and
-- its event exist, or neither does. A separate relay process then reads this
-- table and publishes to Kafka, marking rows as published.
--
-- The relay may publish a row twice (it can crash after sending but before
-- marking), so delivery is AT-LEAST-ONCE and consumers must be idempotent.
-- That is a deliberate trade: at-least-once with idempotent consumers is
-- achievable; exactly-once across two systems generally is not.
-- ============================================================================

DROP TABLE IF EXISTS event_outbox;

CREATE TABLE event_outbox (
    outbox_id     BIGSERIAL   PRIMARY KEY,

    -- What the event is about. aggregate_id becomes the Kafka message KEY,
    -- which is what guarantees ordering per product: Kafka preserves order
    -- within a partition, and the same key always hashes to the same partition.
    aggregate_type VARCHAR(50) NOT NULL,        -- 'StockMovement'
    aggregate_id   VARCHAR(100) NOT NULL,       -- the SKU

    event_type     VARCHAR(80) NOT NULL,        -- 'StockMovementRecorded'
    topic          VARCHAR(120) NOT NULL,       -- 'inventory.stock-movement.v1'

    -- The event body as JSON. The relay converts this to Avro before sending,
    -- so the Avro schema stays owned by the relay rather than by the database.
    payload        JSONB NOT NULL,

    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMP,                   -- NULL until the relay sends it
    attempts       INT NOT NULL DEFAULT 0,
    last_error     TEXT
);

-- The relay's hot query is "give me the oldest unpublished rows".
-- A PARTIAL index keeps it tiny: it only indexes rows still awaiting
-- publication, so it does not grow with the (much larger) published history.
CREATE INDEX idx_outbox_unpublished
    ON event_outbox (created_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_aggregate ON event_outbox (aggregate_type, aggregate_id);


-- ----------------------------------------------------------------------------
-- Useful while working:
-- ----------------------------------------------------------------------------
-- Pending events:
--   SELECT outbox_id, event_type, aggregate_id, created_at
--   FROM event_outbox WHERE published_at IS NULL ORDER BY created_at;
--
-- Publication lag:
--   SELECT COUNT(*) FILTER (WHERE published_at IS NULL) AS pending,
--          COUNT(*) FILTER (WHERE published_at IS NOT NULL) AS published,
--          MAX(EXTRACT(EPOCH FROM (published_at - created_at))) AS worst_lag_seconds
--   FROM event_outbox;
--
-- Replay everything (the relay will re-publish):
--   UPDATE event_outbox SET published_at = NULL;
