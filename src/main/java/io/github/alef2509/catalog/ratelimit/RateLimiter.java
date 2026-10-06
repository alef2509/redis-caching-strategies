package io.github.alef2509.catalog.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Token bucket in a single Lua script: read, refill, take and write happen
 * atomically in Redis, so concurrent requests on different instances cannot both
 * take the last token. The script uses the Redis server clock ({@code TIME}), so
 * instances with skewed clocks still agree.
 */
public class RateLimiter {

    public record Decision(boolean allowed, long remaining, long retryAfterMillis) {
    }

    private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of("""
            local capacity = tonumber(ARGV[1])
            local refill_per_ms = tonumber(ARGV[2]) / 1000
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local tokens = tonumber(state[1]) or capacity
            local updated = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + (now - updated) * refill_per_ms)

            local allowed = 0
            local retry_after = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retry_after = math.ceil((1 - tokens) / refill_per_ms)
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill_per_ms))
            return {allowed, math.floor(tokens), retry_after}
            """, List.class);

    private final StringRedisTemplate redis;
    private final int capacity;
    private final double refillPerSecond;

    public RateLimiter(StringRedisTemplate redis, int capacity, double refillPerSecond) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public Decision tryAcquire(String client) {
        List<?> result = redis.execute(TOKEN_BUCKET, List.of("catalog:ratelimit:" + client),
                Integer.toString(capacity), Double.toString(refillPerSecond));
        return new Decision(((Number) result.get(0)).longValue() == 1, ((Number) result.get(1)).longValue(),
                ((Number) result.get(2)).longValue());
    }

    public int capacity() {
        return capacity;
    }
}
