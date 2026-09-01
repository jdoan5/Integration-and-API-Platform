package com.jdoan.inventory.orchestration.api;

import com.jdoan.inventory.orchestration.config.TemporalConfig;
import com.jdoan.inventory.orchestration.workflow.Dtos;
import com.jdoan.inventory.orchestration.workflow.StockTransferWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Start, signal and query a transfer over plain HTTP.
 *
 * The query endpoint is the one that matters. Phase 4 can tell you what
 * happened, by reading an event log and inferring; this answers "where is it
 * now" from the workflow's own state - including IN_TRANSIT, which is a state
 * no single event represents because it is the gap between two of them.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final WorkflowClient client;

    public TransferController(WorkflowClient client) {
        this.client = client;
    }

    /** Start one. Returns immediately - the workflow outlives this request. */
    @PostMapping
    public Map<String, Object> start(@RequestBody Dtos.TransferRequest request) {
        String workflowId = "transfer-" + request.sku() + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        StockTransferWorkflow workflow = client.newWorkflowStub(
                StockTransferWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TemporalConfig.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        // Bounds the whole process, human wait included. Without
                        // it a workflow awaiting a decision that never comes
                        // lives forever, which is a real way to leak.
                        .setWorkflowExecutionTimeout(Duration.ofMinutes(10))
                        .build());

        WorkflowClient.start(workflow::transfer, request);
        return Map.of("workflowId", workflowId, "status", "STARTED");
    }

    /** Where is it now? Answered from workflow state, not from a log. */
    @GetMapping("/{workflowId}")
    public Dtos.TransferState state(@PathVariable String workflowId) {
        return client.newWorkflowStub(StockTransferWorkflow.class, workflowId).state();
    }

    @PostMapping("/{workflowId}/approve")
    public Map<String, Object> approve(@PathVariable String workflowId,
                                       @RequestParam(defaultValue = "operator") String by) {
        client.newWorkflowStub(StockTransferWorkflow.class, workflowId).approve(by);
        return Map.of("workflowId", workflowId, "signal", "approve", "by", by);
    }

    @PostMapping("/{workflowId}/reject")
    public Map<String, Object> reject(@PathVariable String workflowId,
                                      @RequestParam(defaultValue = "no reason given") String reason) {
        client.newWorkflowStub(StockTransferWorkflow.class, workflowId).reject(reason);
        return Map.of("workflowId", workflowId, "signal", "reject", "reason", reason);
    }

    /** Block until it finishes. Separate from the query on purpose. */
    @GetMapping("/{workflowId}/result")
    public Dtos.TransferResult result(@PathVariable String workflowId) {
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
        return stub.getResult(Dtos.TransferResult.class);
    }
}
