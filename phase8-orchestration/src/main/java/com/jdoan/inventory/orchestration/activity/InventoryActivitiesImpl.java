package com.jdoan.inventory.orchestration.activity;

import com.jdoan.inventory.orchestration.workflow.Dtos;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Calls the Phase 2 REST facade through the Phase 3 gateway. */
@Component
public class InventoryActivitiesImpl implements InventoryActivities {

    private static final Logger log = LoggerFactory.getLogger(InventoryActivitiesImpl.class);

    private final RestClient http;
    private final String apiKey;

    public InventoryActivitiesImpl(@Value("${inventory.api.base-url}") String baseUrl,
                                   @Value("${inventory.api.key}") String apiKey) {
        // RestClient.create rather than an injected RestClient.Builder: Boot 4
        // does not auto-configure the builder unless the restclient starter is
        // present, and one activity calling one API does not need a shared,
        // customised builder to justify the extra dependency.
        this.http = RestClient.create(baseUrl);
        this.apiKey = apiKey;
    }

    @Override
    public Dtos.MovementResult shipOut(String workflowId, String sku, String fromWarehouse, int quantity) {
        return movement(workflowId + ":ship-out", sku, fromWarehouse, "TRANSFER_OUT", quantity,
                "transfer: shipped out");
    }

    @Override
    public Dtos.MovementResult returnToSource(String workflowId, String sku,
                                              String fromWarehouse, int quantity) {
        log.info("COMPENSATING: returning {} x {} to {}", quantity, sku, fromWarehouse);
        return movement(workflowId + ":return", sku, fromWarehouse, "TRANSFER_IN", quantity,
                "compensation: transfer reversed");
    }

    @Override
    public String bookCarrier(String sku, int quantity, String fromWarehouse,
                              String toWarehouse, String simulate) {
        if ("carrier-unavailable".equals(simulate)) {
            // NON-retryable, deliberately. The default is to keep retrying, and
            // a demo that hangs for an hour proves nothing. Real systems make
            // this call too: some upstream errors are worth retrying and some
            // mean stop and unwind.
            throw ApplicationFailure.newNonRetryableFailure(
                    "no carrier available for " + fromWarehouse + " to " + toWarehouse,
                    "CarrierUnavailable");
        }
        String ref = "CN-" + Activity.getExecutionContext().getInfo().getWorkflowId().substring(0, 8);
        log.info("carrier booked for {} x {} : {} -> {} as {}",
                quantity, sku, fromWarehouse, toWarehouse, ref);
        return ref;
    }

    @Override
    public Dtos.MovementResult receiveAt(String workflowId, String sku,
                                         String toWarehouse, int quantity) {
        return movement(workflowId + ":receive", sku, toWarehouse, "TRANSFER_IN", quantity,
                "transfer: received at destination");
    }

    // ------------------------------------------------------------------
    /**
     * One movement, with an idempotency key derived from the workflow id and
     * the step name.
     *
     * NOT from the attempt. Temporal will happily run this activity again after
     * a worker crash, and a key that changed per attempt would move stock twice
     * - the exact bug Phase 5 documents, arriving from a different direction.
     */
    private Dtos.MovementResult movement(String idempotencyKey, String sku, String warehouseCode,
                                         String movementType, int quantity, String notes) {
        return http.post()
                .uri("/api/v1/movements")
                .contentType(MediaType.APPLICATION_JSON)
                .header("apikey", apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of(
                        "sku", sku,
                        "warehouseCode", warehouseCode,
                        "movementType", movementType,
                        "quantity", quantity,
                        "referenceType", "TRANSFER",
                        "notes", notes))
                .retrieve()
                .body(Dtos.MovementResult.class);
    }
}
