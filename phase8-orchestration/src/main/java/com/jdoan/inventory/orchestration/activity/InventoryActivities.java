package com.jdoan.inventory.orchestration.activity;

import com.jdoan.inventory.orchestration.workflow.Dtos;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The side effects. Everything that talks to the outside world lives here.
 *
 * Temporal retries activities, so every one of these can run more than once for
 * a single logical step - the same problem Phase 5 hit when a model held a
 * write tool, and it has the same answer: the Phase 2 facade takes an
 * Idempotency-Key, and the key is derived from the workflow id and the step
 * rather than generated per attempt.
 *
 * Worth stating plainly. Temporal does not make retries safe. It makes them
 * CERTAIN, and the endpoint has to be idempotent anyway.
 */
@ActivityInterface
public interface InventoryActivities {

    /** Take the goods out of the source warehouse. */
    @ActivityMethod
    Dtos.MovementResult shipOut(String workflowId, String sku, String fromWarehouse, int quantity);

    /** The compensation for shipOut: put them back where they came from. */
    @ActivityMethod
    Dtos.MovementResult returnToSource(String workflowId, String sku, String fromWarehouse, int quantity);

    /** A flaky external carrier. Fails when simulate says so. */
    @ActivityMethod
    String bookCarrier(String sku, int quantity, String fromWarehouse, String toWarehouse, String simulate);

    /** Book the goods in at the destination. */
    @ActivityMethod
    Dtos.MovementResult receiveAt(String workflowId, String sku, String toWarehouse, int quantity);
}
