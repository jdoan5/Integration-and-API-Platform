package com.jdoan.inventory.orchestration.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A stock transfer between warehouses, owned end to end.
 *
 * Phase 4 does the opposite on purpose: a movement emits an event and
 * independent consumers react, nobody in charge. That is the right shape for
 * reacting to something that already happened. It is the wrong shape for a
 * process that must COMPLETE, because no one owns the outcome, there is no
 * place to put a retry that outlives a process, and "where is transfer X now"
 * can only be answered by replaying the log and inferring.
 *
 * A transfer is the honest example because it has a genuine in-between state.
 * Once the goods have left the source warehouse and not yet arrived at the
 * destination, they exist in neither - and if anything fails from that point,
 * somebody has to put them back. That "somebody" is what this phase is about.
 *
 * The three annotations are the whole difference:
 *   @WorkflowMethod  the process, written as ordinary sequential code
 *   @SignalMethod    something outside changing its mind, durably
 *   @QueryMethod     asking where it is, answered from state rather than logs
 */
@WorkflowInterface
public interface StockTransferWorkflow {

    @WorkflowMethod
    Dtos.TransferResult transfer(Dtos.TransferRequest request);

    /** A human releasing the shipment. Survives a restart of everything. */
    @SignalMethod
    void approve(String approver);

    @SignalMethod
    void reject(String reason);

    /** Where is it now? */
    @QueryMethod
    Dtos.TransferState state();
}
