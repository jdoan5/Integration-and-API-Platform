package com.jdoan.inventory.rest.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed-window rate limiter backed by Redis.
 *
 * WHY LUA: the naive version is
 *     count = INCR key
 *     if count == 1: EXPIRE key window
 * which has a race. Two clients can both see count==1 and both set the TTL,
 * or worse, a crash between INCR and EXPIRE leaves a key with NO expiry -
 * permanently rate-limiting that caller.
 *
 * Redis executes a Lua script ATOMICALLY: no other command runs in between.
 * INCR and EXPIRE become one indivisible operation. This is the standard
 * reason distributed rate limiters are written as scripts.
 *
 * Redis (rather than in-memory state) is what makes the limit correct across
 * MULTIPLE instances of this service - the same reason Kong's rate-limiting
 * plugin uses Redis in Phase 3.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
                return 0
            end
            return 1
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(LUA, Long.class);
    }

    /**
     * @return true if the call is allowed, false if the limit is exceeded.
     *
     * FAIL-OPEN: if Redis is unreachable this returns true and traffic flows.
     *
     * That is a deliberate trade-off, and the opposite choice is equally
     * defensible:
     *   fail-OPEN  - a Redis outage degrades protection, not availability.
     *                Your API stays up; an abusive client is briefly unthrottled.
     *   fail-CLOSED - a Redis outage takes the whole API down, converting a
     *                cache outage into a total outage.
     *
     * For a public API guarding against accidental overload, fail-open is
     * usually right - it is what Kong's `fault_tolerant: true` does in Phase 3.
     * For a limiter enforcing paid quotas or protecting a fragile backend,
     * fail-closed may be correct instead. The point is to CHOOSE, and to know
     * which one you chose.
     */
    public boolean allow(String clientKey, int limit, int windowSeconds) {
        try {
            Long allowed = redis.execute(script,
                    List.of("ratelimit:" + clientKey),
                    String.valueOf(limit),
                    String.valueOf(windowSeconds));
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            log.warn("Rate limiter unavailable (Redis down) - failing OPEN for '{}': {}",
                    clientKey, e.getMessage());
            return true;
        }
    }

    /** Calls remaining in the current window. Returns the full limit if Redis is down. */
    public long remaining(String clientKey, int limit) {
        try {
            String used = redis.opsForValue().get("ratelimit:" + clientKey);
            long consumed = used == null ? 0 : Long.parseLong(used);
            return Math.max(0, limit - consumed);
        } catch (Exception e) {
            return limit;
        }
    }
}
