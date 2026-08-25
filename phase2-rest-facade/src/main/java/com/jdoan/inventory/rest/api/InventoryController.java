package com.jdoan.inventory.rest.api;

import com.jdoan.inventory.rest.cache.RateLimiter;
import com.jdoan.inventory.rest.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * The REST facade.
 *
 * This is the STRANGLER FIG pattern: new consumers get clean JSON over HTTP
 * while the SOAP contract keeps serving the ones that already exist. Nothing
 * is rewritten, and the two can coexist indefinitely.
 *
 * Notice the API is NOT a 1:1 translation of the SOAP operations - REST
 * models resources (/products/{sku}, /stock/{sku}) where SOAP models verbs
 * (GetProduct, GetStockLevel). A facade that merely renames the operations
 * would inherit the old design's shape and gain nothing.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class InventoryController {

    private final InventoryService service;
    private final RateLimiter rateLimiter;

    private static final int RATE_LIMIT = 60;      // calls
    private static final int RATE_WINDOW = 60;     // per seconds

    public InventoryController(InventoryService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    // ------------------------------------------------------------------
    @GetMapping("/products/{sku}")
    public ResponseEntity<ProductDto> getProduct(
            @PathVariable
            @Pattern(regexp = "[A-Z]{3,4}-[A-Z0-9]{3,5}-?[0-9]{0,5}",
                     message = "SKU format is invalid")
            String sku,
            @RequestHeader(value = "X-Client-Id", defaultValue = "anonymous") String clientId) {

        enforceRateLimit(clientId);
        return ResponseEntity.ok()
                .headers(rateLimitHeaders(clientId))
                .body(service.getProduct(sku));
    }

    // ------------------------------------------------------------------
    @GetMapping("/stock/{sku}")
    public ResponseEntity<List<StockLevelDto>> getStock(
            @PathVariable String sku,
            @RequestParam(required = false) String warehouse,
            @RequestHeader(value = "X-Client-Id", defaultValue = "anonymous") String clientId) {

        enforceRateLimit(clientId);
        return ResponseEntity.ok()
                .headers(rateLimitHeaders(clientId))
                .body(service.getStockLevels(sku, warehouse));
    }

    // ------------------------------------------------------------------
    @GetMapping("/low-stock")
    public ResponseEntity<Map<String, Object>> lowStock(
            @RequestParam(required = false) String warehouse,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestHeader(value = "X-Client-Id", defaultValue = "anonymous") String clientId) {

        enforceRateLimit(clientId);
        return ResponseEntity.ok()
                .headers(rateLimitHeaders(clientId))
                .body(service.listLowStock(warehouse, limit));
    }

    // ------------------------------------------------------------------
    /**
     * The write. Supports an Idempotency-Key header: replaying the same key
     * returns the FIRST result instead of recording a second movement.
     *
     * This matters because a client that times out and retries would
     * otherwise double-count stock. Idempotency keys are how payment APIs
     * solve the same problem.
     */
    @PostMapping("/movements")
    public ResponseEntity<MovementResultDto> recordMovement(
            @Valid @RequestBody MovementRequestDto request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Client-Id", defaultValue = "anonymous") String clientId) {

        enforceRateLimit(clientId);
        MovementResultDto result = service.recordMovement(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .headers(rateLimitHeaders(clientId))
                .body(result);
    }

    // ------------------------------------------------------------------
    /** Cache statistics, so you can SEE the cache working. */
    @GetMapping("/_cache/stats")
    public Map<String, Object> cacheStats() {
        return service.cacheStats();
    }

    @DeleteMapping("/_cache")
    public Map<String, Object> clearCache() {
        return Map.of("evicted", service.clearCache());
    }

    // ------------------------------------------------------------------
    private void enforceRateLimit(String clientId) {
        if (!rateLimiter.allow(clientId, RATE_LIMIT, RATE_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded: %d requests per %ds".formatted(RATE_LIMIT, RATE_WINDOW));
        }
    }

    private HttpHeaders rateLimitHeaders(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Limit", String.valueOf(RATE_LIMIT));
        headers.add("X-RateLimit-Remaining", String.valueOf(rateLimiter.remaining(clientId, RATE_LIMIT)));
        return headers;
    }
}
