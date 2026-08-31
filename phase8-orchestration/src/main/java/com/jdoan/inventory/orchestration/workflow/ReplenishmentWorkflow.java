package com.jdoan.inventory.orchestration.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A replenishment, owned end to end.
 *
 * Phase 4 does the opposite of this on purpose: a movement emits an event and
 * independent consumers react, nobody in charge. That is the right shape for
 * reacting to something that already happened. It is the wrong shape for a
 * process that must COMPLETE, because there is no one to own the outcome, no
 * place to put a retry that outlives a process, and no way to answer "where is
 * this now" without replaying the log and inferring.
 *
 * The three annotations below are the whole difference:
 *   @WorkflowMethod  the process, written as ordinary sequential code
 *   @SignalMethod    something outside changing its mind, durably
 *   @QueryMethod     asking where it is, answered from state rather than logs
 */
@WorkflowInterface
public interface ReplenishmentWorkflow {

    @WorkflowMethod
    Dtos.ReplenishmentResult replenish(Dtos.ReplenishmentRequest request);

    /** A human approving the purchase. Survives a restart of everything. */
    @SignalMethod
    void approve(String approver);

    @SignalMethod
    void reject(String reason);

    /** Where is it now? */
    @QueryMethod
    Dtos.ReplenishmentState state();
}
