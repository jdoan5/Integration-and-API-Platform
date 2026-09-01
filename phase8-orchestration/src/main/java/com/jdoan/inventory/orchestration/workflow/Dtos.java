package com.jdoan.inventory.orchestration.workflow;

/** The shapes crossing the workflow boundary. Records, so they serialise cleanly. */
public final class Dtos {

    private Dtos() {}

    /**
     * Move {@code quantity} of {@code sku} from one warehouse to another.
     *
     * @param simulate deterministic failure injection for the demo:
     *                 "carrier-unavailable" makes the carrier booking fail so
     *                 the compensation path runs. Empty means a normal run.
     *                 A random failure rate would be more realistic and would
     *                 make the verification script flaky, which is worse.
     */
    public record TransferRequest(
            String sku, String fromWarehouse, String toWarehouse, int quantity, String simulate) {}

    public record TransferResult(
            String status, long outboundMovementId, long inboundMovementId, String detail) {}

    /**
     * What a QUERY returns.
     *
     * This is the thing Phase 4 structurally cannot do. Choreography can tell
     * you what happened; only something that owns the process can tell you
     * where the process IS - including that the goods are currently in transit,
     * which is a state no single event represents.
     */
    public record TransferState(
            String stage, String sku, String fromWarehouse, String toWarehouse,
            int quantity, String decision, String note) {}

    public record MovementResult(
            long movementId, String sku, String warehouseCode,
            int quantityBefore, int quantityAfter, int delta,
            String recordedAt, boolean replayed) {}
}
