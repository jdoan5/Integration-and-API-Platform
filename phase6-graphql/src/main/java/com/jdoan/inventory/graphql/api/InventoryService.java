package com.jdoan.inventory.graphql.api;

import com.jdoan.inventory.graphql.soapclient.InventorySoapClient;
import com.jdoan.inventory.graphql.soapclient.UpstreamFaultException;
import com.jdoan.inventory.graphql.soapclient.generated.ListLowStockResponse;
import com.jdoan.inventory.graphql.soapclient.generated.ProductType;
import com.jdoan.inventory.graphql.soapclient.generated.RecordStockMovementResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Maps the SOAP backend onto the GraphQL types, and caches what it can.
 *
 * THE CACHING LESSON LIVES HERE. Phase 2 cached whole HTTP responses under the
 * request URL, because in REST the URL IS the cache key: /products/ELEC-LAP-001
 * always means the same bytes. GraphQL has one URL and a POST body, and two
 * clients asking for different field selections of the same product get
 * different responses from the same URL. Response caching is therefore not
 * merely harder, it is meaningless.
 *
 * What survives is caching one layer down: cache the ENTITY, keyed by its
 * identity, and let each query assemble whatever shape it asked for. The TTLs
 * are Phase 2's, but the key is the SKU rather than the URL - and that is the
 * whole difference.
 */
@Service
public class InventoryService {

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(5);

    private final InventorySoapClient soap;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean cacheEnabled;

    public InventoryService(InventorySoapClient soap, StringRedisTemplate redis, ObjectMapper json,
                            @Value("${inventory.graphql.cache.enabled:true}") boolean cacheEnabled) {
        this.soap = soap;
        this.redis = redis;
        this.json = json;
        this.cacheEnabled = cacheEnabled;
    }

    // ------------------------------------------------------------------
    public Types.Product getProduct(String sku) {
        String key = "gql:product:" + sku;

        String cached = cacheGet(key);
        if (cached != null) {
            try {
                return json.readValue(cached, Types.Product.class);
            } catch (Exception ignored) {
                // A poisoned entry is not worth failing a query over.
            }
        }

        Types.Product product = translateFaults(() -> {
            ProductType p = soap.getProduct(sku);
            return new Types.Product(p.getSku(), p.getName(), p.getDescription(), p.getCategory(),
                    p.getUnitPrice().doubleValue(), p.getUnitCost().doubleValue(),
                    p.getReorderPoint(), p.getReorderQuantity(), p.isActive());
        });

        if (product != null) {
            cachePut(key, product, PRODUCT_TTL);
        }
        return product;
    }

    public List<Types.StockLevel> getStockLevels(String sku, String warehouseCode) {
        return translateFaults(() -> soap.getStockLevels(sku, warehouseCode).stream()
                .map(s -> new Types.StockLevel(
                        s.getSku(), s.getProductName(),
                        WarehouseCodes.toGraphql(s.getWarehouseCode()), s.getWarehouseName(),
                        s.getQuantity(), s.getReorderPoint(),
                        s.getQuantity() < s.getReorderPoint()))
                .toList());
    }

    public List<Types.LowStockItem> listLowStock(String warehouseCode, Integer limit) {
        ListLowStockResponse response = soap.listLowStock(warehouseCode, limit);
        return response.getItem().stream()
                .map(i -> new Types.LowStockItem(
                        i.getSku(), i.getProductName(),
                        WarehouseCodes.toGraphql(i.getWarehouseCode()),
                        i.getQuantity(), i.getReorderPoint(),
                        i.getDeficit(), i.getSuggestedOrderQty()))
                .toList();
    }

    public Types.MovementResult recordMovement(Types.MovementInput input) {
        // Idempotency, exactly as Phase 2 does it - except the key arrives as a
        // schema field rather than an HTTP header, because GraphQL has no
        // header convention to borrow.
        String idemKey = input.idempotencyKey();
        if (idemKey != null && !idemKey.isBlank()) {
            String stored = idempotencyGet("gql:idem:" + idemKey);
            if (stored != null) {
                try {
                    Types.MovementResult prior = json.readValue(stored, Types.MovementResult.class);
                    return new Types.MovementResult(prior.movementId(), prior.sku(), prior.warehouseCode(),
                            prior.quantityBefore(), prior.quantityAfter(), prior.delta(),
                            prior.recordedAt(), true);
                } catch (Exception ignored) {
                    // fall through and record it
                }
            }
        }

        String domainWarehouse = WarehouseCodes.toDomain(input.warehouseCode());
        RecordStockMovementResponse response = translateFaults(() ->
                soap.recordMovement(input.sku(), domainWarehouse, input.movementType(),
                        input.quantity(), input.referenceType(), input.notes()));

        Types.MovementResult result = new Types.MovementResult(
                String.valueOf(response.getMovementId()), response.getSku(),
                WarehouseCodes.toGraphql(response.getWarehouseCode()),
                response.getQuantityBefore(), response.getQuantityAfter(),
                response.getQuantityAfter() - response.getQuantityBefore(),
                String.valueOf(response.getRecordedAt()), false);

        // The write makes the cached product stale only if reorder policy changed,
        // but stock is not cached here at all - see the class comment.
        if (idemKey != null && !idemKey.isBlank()) {
            cachePut("gql:idem:" + idemKey, result, Duration.ofHours(24));
        }
        return result;
    }

    // ------------------------------------------------------------------
    private <T> T translateFaults(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (UpstreamFaultException fault) {
            if (fault.getCode().endsWith("NOT_FOUND")) {
                // Absence is not an error in GraphQL: the field is nullable and
                // null is the answer. Throwing here would fail sibling fields
                // that resolved perfectly well.
                return null;
            }
            throw fault;
        }
    }

    /**
     * The idempotency lookup, which must NOT swallow a Redis failure.
     *
     * FOUND WHEN DOCKER STOPPED MID-SESSION. Every other cache read here treats
     * an unreachable Redis as a miss, which is right: the cache is an
     * optimisation and a read should still succeed without it. Routing the
     * idempotency check through that same helper made an unreachable Redis look
     * like "no prior call", so two identical mutations wrote two movements -
     * movementId 42, then 43 - while promising to be safe to retry.
     *
     * That is the Phase 2 cache bug turned inside out. There the cache became a
     * hard dependency and reads failed; here it stayed soft where it must be
     * hard, and writes duplicated silently. Failing open is correct for reads
     * and dangerous for writes, and the two paths need different code.
     *
     * So this one fails CLOSED: a caller who supplied an idempotency key asked
     * for a guarantee, and refusing is the honest answer when it cannot be
     * given. A caller who supplied no key never asked, and is unaffected.
     */
    private String idempotencyGet(String key) {
        if (!cacheEnabled) {
            throw new IdempotencyUnavailableException(
                    "An idempotencyKey was supplied but the idempotency store is disabled, "
                    + "so a retry cannot be made safe. Retry without the key to accept that "
                    + "risk, or enable the cache.");
        }
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            throw new IdempotencyUnavailableException(
                    "An idempotencyKey was supplied but the idempotency store is unreachable, "
                    + "so this mutation cannot be made safe to retry. Refusing rather than "
                    + "risking a duplicate movement.", e);
        }
    }

    private String cacheGet(String key) {
        if (!cacheEnabled) return null;
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            return null;   // the cache is an optimisation, never a dependency
        }
    }

    private void cachePut(String key, Object value, Duration ttl) {
        if (!cacheEnabled) return;
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
            // same reasoning as cacheGet
        }
    }

    public long clearCache() {
        try {
            var keys = redis.keys("gql:*");
            if (keys == null || keys.isEmpty()) return 0;
            redis.delete(keys);
            return keys.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
