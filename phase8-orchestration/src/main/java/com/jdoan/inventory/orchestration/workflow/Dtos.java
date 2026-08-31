package com.jdoan.inventory.orchestration.workflow;

/** The shapes crossing the workflow boundary. Records, so they serialise cleanly. */
public final class Dtos {

    private Dtos() {}

    /**
     * @param simulate deterministic failure injection for the demo:
     *                 "supplier-unavailable" makes the supplier call fail every
     *                 attempt so the compensation path runs. Empty means normal.
     *                 A random failure rate would be more realistic and would
     *                 make the verification script flaky, which is worse.
     */
    public record ReplenishmentRequest(
            String sku, String warehouseCode, int quantity, String simulate) {}

    public record ReplenishmentResult(
            String status, long reservationMovementId, long receiptMovementId, String detail) {}

    /**
     * What a QUERY returns.
     *
     * This is the thing Phase 4 structurally cannot do. Choreography can tell
     * you what happened; only something that owns the process can tell you
     * where the process IS.
     */
    public record ReplenishmentState(
            String stage, String sku, String warehouseCode, int quantity,
            boolean approved, String note) {}

    public record MovementResult(
            long movementId, String sku, String warehouseCode,
            int quantityBefore, int quantityAfter, int delta,
            String recordedAt, boolean replayed) {}
}
