package com.jdoan.inventory.orchestration.workflow;

import com.jdoan.inventory.orchestration.activity.InventoryActivities;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * The saga, written as ordinary sequential code.
 *
 * That is the trick worth noticing: this reads like a method, and it is
 * durable. The worker can be killed between any two lines and the process
 * resumes on another one with its local variables intact, because Temporal
 * replays the history rather than keeping a thread alive. Nothing here is
 * persisted by hand and there is no state machine to maintain.
 *
 * Phase 4 gets its durability from the outbox: the event commits with the
 * business change, and consumers are idempotent because delivery is
 * at-least-once. Both survive a crash. The difference is who is responsible for
 * FINISHING, and whether anything can answer "where is it now".
 */
public class StockTransferWorkflowImpl implements StockTransferWorkflow {

    /**
     * Timeouts, and why these numbers.
     *
     * START_TO_CLOSE bounds ONE attempt. The mistake is setting only
     * scheduleToClose, which bounds the whole retry sequence - one hung attempt
     * then eats the entire budget and no retry ever happens.
     */
    private static final ActivityOptions INVENTORY_CALL = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMillis(500))
                    .setMaximumAttempts(4)
                    .build())
            .build();

    private static final ActivityOptions CARRIER_CALL = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMillis(500))
                    .setMaximumAttempts(3)
                    .build())
            .build();

    /** How long a human gets before the transfer is unwound. */
    private static final Duration APPROVAL_WINDOW =
            Duration.ofSeconds(Long.getLong("transfer.approval.seconds", 30));

    private final InventoryActivities inventory =
            Workflow.newActivityStub(InventoryActivities.class, INVENTORY_CALL);
    private final InventoryActivities carrier =
            Workflow.newActivityStub(InventoryActivities.class, CARRIER_CALL);

    // Ordinary fields. Temporal replays them, so a query reads the real current
    // state rather than a projection someone has to maintain.
    private String stage = "STARTING";
    private long outboundMovementId = 0;
    private Boolean approved = null;
    private String note = "";
    private Dtos.TransferRequest request;

    @Override
    public Dtos.TransferResult transfer(Dtos.TransferRequest req) {
        this.request = req;
        String workflowId = Workflow.getInfo().getWorkflowId();

        // parallelCompensation(false): compensations run in REVERSE order, one
        // at a time. With one compensation it changes nothing; with several it
        // is the difference between unwinding and a race.
        Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());

        try {
            stage = "SHIPPING_OUT";
            Dtos.MovementResult outbound =
                    inventory.shipOut(workflowId, req.sku(), req.fromWarehouse(), req.quantity());
            outboundMovementId = outbound.movementId();

            // Registered IMMEDIATELY after the step it undoes, not at the end.
            // From here on the goods are in transit and belong to neither
            // warehouse, so anything that fails has to put them back.
            saga.addCompensation(() -> inventory.returnToSource(
                    workflowId, req.sku(), req.fromWarehouse(), req.quantity()));

            stage = "IN_TRANSIT";
            String consignment = carrier.bookCarrier(
                    req.sku(), req.quantity(), req.fromWarehouse(), req.toWarehouse(), req.simulate());
            note = "consignment " + consignment;

            stage = "AWAITING_APPROVAL";
            // A durable wait. No thread is blocked, nothing polls, and if every
            // process dies the timer survives in Temporal rather than in a
            // scheduler somebody has to keep running.
            boolean decided = Workflow.await(APPROVAL_WINDOW, () -> approved != null);

            if (!decided) {
                return unwind(saga, "TIMED_OUT", outbound.movementId(),
                        "no decision within " + APPROVAL_WINDOW.toSeconds() + "s");
            }
            if (!approved) {
                return unwind(saga, "REJECTED", outbound.movementId(), note);
            }

            stage = "RECEIVING";
            Dtos.MovementResult inbound =
                    inventory.receiveAt(workflowId, req.sku(), req.toWarehouse(), req.quantity());

            stage = "COMPLETED";
            return new Dtos.TransferResult(
                    "COMPLETED", outbound.movementId(), inbound.movementId(), note);

        } catch (ActivityFailure failure) {
            // The goods have already left the source warehouse, so this cannot
            // just be reported - it has to be undone.
            // The outbound id, not 0: a compensated transfer DID move stock and
            // then move it back, and a result that hides the first half makes
            // the ledger look inexplicable to whoever reads it later.
            return unwind(saga, "FAILED", outboundMovementId, rootCause(failure));
        }
    }

    private Dtos.TransferResult unwind(Saga saga, String status, long outboundId, String why) {
        stage = "COMPENSATING";
        note = why;
        saga.compensate();
        stage = "COMPENSATED";
        return new Dtos.TransferResult(status, outboundId, 0, why);
    }

    @Override
    public void approve(String approver) {
        this.approved = true;
        this.note = "approved by " + approver;
    }

    @Override
    public void reject(String reason) {
        this.approved = false;
        this.note = "rejected: " + reason;
    }

    @Override
    public Dtos.TransferState state() {
        return new Dtos.TransferState(
                stage,
                request == null ? null : request.sku(),
                request == null ? null : request.fromWarehouse(),
                request == null ? null : request.toWarehouse(),
                request == null ? 0 : request.quantity(),
                // A tri-state, not a boolean. `approved == null` means still
                // waiting, and rendering that as `false` reads as "rejected" -
                // the opposite of the truth, on the one field an operator
                // looks at while deciding whether to chase someone.
                approved == null ? "PENDING" : (approved ? "APPROVED" : "REJECTED"),
                note);
    }

    /** Temporal wraps failures; the message a human wants is at the bottom. */
    private static String rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? t.toString() : current.getMessage();
    }
}
