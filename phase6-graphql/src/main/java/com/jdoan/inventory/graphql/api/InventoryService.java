package com.jdoan.inventory.graphql.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

/**
 * Caching and idempotency over whichever {@link InventoryBackend} is bound.
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
 *
 * The JAXB mapping that used to live here moved to SoapInventoryBackend when
 * the public demo needed a second implementation. Everything in this class is
 * indifferent to which one it is talking to.
 */
@Service
public class InventoryService {

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(5);

    private final InventoryBackend backend;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean cacheEnabled;

    public InventoryService(InventoryBackend backend, StringRedisTemplate redis, ObjectMapper json,
                            @Value("${inventory.graphql.cache.enabled:true}") boolean cacheEnabled) {
        this.backend = backend;
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

        Types.Product product = backend.getProduct(sku);
        if (product != null) {
            cachePut(key, product, PRODUCT_TTL);
        }
        return product;
    }

    public List<Types.StockLevel> getStockLevels(String sku, String warehouseCode) {
        return backend.getStockLevels(sku, warehouseCode);
    }

    public List<Types.LowStockItem> listLowStock(String warehouseCode, Integer limit) {
        return backend.listLowStock(warehouseCode, limit);
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

        Types.MovementResult result = backend.recordMovement(input);

        if (idemKey != null && !idemKey.isBlank()) {
            cachePut("gql:idem:" + idemKey, result, Duration.ofHours(24));
        }
        return result;
    }

    // ------------------------------------------------------------------
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
        if (!cacheEnabled) return 0;
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
