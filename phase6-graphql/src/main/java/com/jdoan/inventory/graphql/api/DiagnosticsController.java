package com.jdoan.inventory.graphql.api;

import com.jdoan.inventory.graphql.soapclient.BackendCallCounter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain HTTP, deliberately.
 *
 * This exposes how many backend calls a GraphQL query actually caused, and it
 * is NOT a GraphQL field: asking the schema how expensive the schema is would
 * change the number while reading it. test-graphql.sh resets this, runs one
 * query, and reads the count - which is how the N+1 figures in the README are
 * produced rather than asserted.
 */
@RestController
@RequestMapping("/diagnostics")
public class DiagnosticsController {

    private final BackendCallCounter counter;
    private final InventoryService service;

    public DiagnosticsController(BackendCallCounter counter, InventoryService service) {
        this.counter = counter;
        this.service = service;
    }

    @GetMapping("/backend-calls")
    public Map<String, Object> backendCalls() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", counter.total());
        out.put("byOperation", counter.snapshot());
        return out;
    }

    @DeleteMapping("/backend-calls")
    public Map<String, Object> reset() {
        counter.reset();
        return Map.of("reset", true);
    }

    /** Clears the entity cache, so a measurement starts from a known state. */
    @DeleteMapping("/cache")
    public Map<String, Object> clearCache() {
        return Map.of("evicted", service.clearCache());
    }
}
