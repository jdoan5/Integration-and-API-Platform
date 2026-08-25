package com.jdoan.inventory.rest.service;

import tools.jackson.databind.ObjectMapper;   // Jackson 3 (Spring Boot 4) moved this package
import com.jdoan.inventory.rest.api.*;
import com.jdoan.inventory.rest.soapclient.InventorySoapClient;
import com.jdoan.inventory.rest.soapclient.generated.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.jdoan.inventory.rest.soapclient.UpstreamFaultException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Business layer: calls SOAP, caches in Redis, maps to REST DTOs.
 *
 * THE CACHE-ASIDE PATTERN (also called lazy loading):
 *   1. look in the cache
 *   2. on a miss, call the source of truth
 *   3. write the result into the cache with a TTL
 *   4. on a WRITE, invalidate the affected keys
 *
 * Step 4 is the one people forget, and it is the reason "there are only two
 * hard things in computer science" is a joke about cache invalidation. Record
 * a movement and the cached stock level is instantly stale; a TTL alone means
 * serving wrong numbers until it expires.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(5);
    private static final Duration STOCK_TTL   = Duration.ofSeconds(30);
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final InventorySoapClient soap;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    private final AtomicLong hits   = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public InventoryService(InventorySoapClient soap, StringRedisTemplate redis, ObjectMapper json) {
        this.soap = soap;
        this.redis = redis;
        this.json = json;
    }

    // ==================================================================
    // READ: product  (long TTL - product data changes rarely)
    // ==================================================================
    public ProductDto getProduct(String sku) {
        String key = "product:" + sku;

        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            hits.incrementAndGet();
            log.debug("cache HIT  {}", key);
            return readJson(cached, ProductDto.class);
        }

        misses.incrementAndGet();
        log.debug("cache MISS {} -> calling SOAP", key);

        ProductDto dto = translateFaults(() -> {
            ProductType p = soap.getProduct(sku);
            return new ProductDto(p.getSku(), p.getName(), p.getDescription(), p.getCategory(),
                    p.getUnitPrice(), p.getUnitCost(),
                    p.getReorderPoint(), p.getReorderQuantity(), p.isActive());
        }, sku);

        redis.opsForValue().set(key, writeJson(dto), PRODUCT_TTL);
        return dto;
    }

    // ==================================================================
    // READ: stock levels  (SHORT TTL - stock changes constantly)
    // ==================================================================
    public List<StockLevelDto> getStockLevels(String sku, String warehouseCode) {
        String key = "stock:" + sku + ":" + (warehouseCode == null ? "ALL" : warehouseCode);

        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            hits.incrementAndGet();
            try {
                return Arrays.asList(json.readValue(cached, StockLevelDto[].class));
            } catch (Exception e) {
                redis.delete(key);   // poisoned entry: drop it and fall through
            }
        }

        misses.incrementAndGet();
        List<StockLevelDto> result = translateFaults(() ->
                soap.getStockLevels(sku, warehouseCode).stream()
                        .map(s -> new StockLevelDto(
                                s.getSku(), s.getProductName(),
                                s.getWarehouseCode(), s.getWarehouseName(),
                                s.getQuantity(), s.getReorderPoint(),
                                s.getQuantity() < s.getReorderPoint()))
                        .toList(), sku);

        redis.opsForValue().set(key, writeJson(result), STOCK_TTL);
        return result;
    }

    // ==================================================================
    // READ: low stock report  (not cached - it is a live operational report)
    // ==================================================================
    public Map<String, Object> listLowStock(String warehouseCode, Integer limit) {
        ListLowStockResponse response = soap.listLowStock(warehouseCode, limit);
        List<Map<String, Object>> items = response.getItem().stream()
                .map(i -> Map.<String, Object>of(
                        "sku", i.getSku(),
                        "productName", i.getProductName(),
                        "warehouseCode", i.getWarehouseCode(),
                        "quantity", i.getQuantity(),
                        "reorderPoint", i.getReorderPoint(),
                        "deficit", i.getDeficit(),
                        "suggestedOrderQty", i.getSuggestedOrderQty()))
                .toList();
        return Map.of("totalCount", response.getTotalCount(), "items", items);
    }

    // ==================================================================
    // WRITE: record a movement, then INVALIDATE what it made stale
    // ==================================================================
    public MovementResultDto recordMovement(MovementRequestDto request, String idempotencyKey) {

        // --- idempotency: replay the stored result instead of writing twice ---
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String stored = redis.opsForValue().get("idem:" + idempotencyKey);
            if (stored != null) {
                log.info("idempotency replay for key {}", idempotencyKey);
                MovementResultDto prior = readJson(stored, MovementResultDto.class);
                return new MovementResultDto(prior.movementId(), prior.sku(), prior.warehouseCode(),
                        prior.quantityBefore(), prior.quantityAfter(), prior.delta(),
                        prior.recordedAt(), true);
            }
        }

        RecordStockMovementResponse response = translateFaults(() ->
                soap.recordMovement(request.sku(), request.warehouseCode(),
                        request.movementType(), request.quantity(),
                        request.referenceType(), request.notes()), request.sku());

        MovementResultDto result = new MovementResultDto(
                response.getMovementId(), response.getSku(), response.getWarehouseCode(),
                response.getQuantityBefore(), response.getQuantityAfter(),
                response.getQuantityAfter() - response.getQuantityBefore(),
                String.valueOf(response.getRecordedAt()), false);

        // --- THE CRITICAL STEP: invalidate every stock key this write affected ---
        // Miss this and reads keep serving pre-write numbers until the TTL expires.
        invalidateStock(request.sku());

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            redis.opsForValue().set("idem:" + idempotencyKey, writeJson(result), IDEMPOTENCY_TTL);
        }
        return result;
    }

    /** Deletes every cached stock entry for a SKU, across all warehouses. */
    private void invalidateStock(String sku) {
        Set<String> keys = redis.keys("stock:" + sku + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            log.info("invalidated {} stock cache key(s) for {}", keys.size(), sku);
        }
    }

    // ==================================================================
    // Cache introspection
    // ==================================================================
    public Map<String, Object> cacheStats() {
        long h = hits.get(), m = misses.get(), total = h + m;
        Set<String> productKeys = redis.keys("product:*");
        Set<String> stockKeys   = redis.keys("stock:*");
        return Map.of(
                "hits", h,
                "misses", m,
                "hitRatePercent", total == 0 ? 0.0 : Math.round(1000.0 * h / total) / 10.0,
                "cachedProducts", productKeys == null ? 0 : productKeys.size(),
                "cachedStockEntries", stockKeys == null ? 0 : stockKeys.size());
    }

    public long clearCache() {
        Set<String> keys = new HashSet<>();
        Optional.ofNullable(redis.keys("product:*")).ifPresent(keys::addAll);
        Optional.ofNullable(redis.keys("stock:*")).ifPresent(keys::addAll);
        if (keys.isEmpty()) return 0;
        Long deleted = redis.delete(keys);
        hits.set(0); misses.set(0);
        return deleted == null ? 0 : deleted;
    }

    // ==================================================================
    // SOAP fault  ->  HTTP status
    // ==================================================================
    private <T> T translateFaults(java.util.function.Supplier<T> call, String subject) {
        try {
            return call.get();
        } catch (UpstreamFaultException fault) {
            // The structured fault detail from Phase 1 pays off here: we branch
            // on a CODE, not on the wording of an English message.
            if (fault.getCode().endsWith("NOT_FOUND")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: " + subject);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Upstream SOAP fault [" + fault.getCode() + "]: " + fault.getMessage());
        } catch (org.springframework.ws.client.WebServiceIOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Inventory SOAP service is unreachable");
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize for cache", e); }
    }

    private <T> T readJson(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Could not deserialize from cache", e); }
    }
}
