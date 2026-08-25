package com.jdoan.inventory.rest.cache;

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
     */
    public boolean allow(String clientKey, int limit, int windowSeconds) {
        Long allowed = redis.execute(script,
                List.of("ratelimit:" + clientKey),
                String.valueOf(limit),
                String.valueOf(windowSeconds));
        return allowed != null && allowed == 1L;
    }

    /** Calls remaining in the current window (never negative). */
    public long remaining(String clientKey, int limit) {
        String used = redis.opsForValue().get("ratelimit:" + clientKey);
        long consumed = used == null ? 0 : Long.parseLong(used);
        return Math.max(0, limit - consumed);
    }
}
