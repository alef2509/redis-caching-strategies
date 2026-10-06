package io.github.alef2509.catalog.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Cache-aside for expensive, popular keys, protected against <b>cache stampedes</b>
 * (a hot key expires and hundreds of requests hit the database at once).
 *
 * <ol>
 *   <li><b>Probabilistic early refresh (XFetch)</b>: each read may decide to refresh
 *       before expiry, with a probability that grows as expiry approaches and with
 *       the cost of the last computation. Hot keys are refreshed ahead of time
 *       instead of all at once at the expiry instant.</li>
 *   <li><b>Single flight across instances</b>: only the holder of a short Redis lock
 *       ({@code SET NX PX}) recomputes; the others keep serving the current value, or
 *       briefly wait for the new one on a cold miss.</li>
 *   <li><b>TTL jitter</b> (±10 %) so keys written together do not expire together.</li>
 * </ol>
 * Reference: Vattani, Chierichetti, Lowenstein, "Optimal Probabilistic Cache Stampede
 * Prevention", VLDB 2015.
 */
public class StampedeProtectedCache {

    /** Stored value plus what XFetch needs: cost of the computation and logical expiry. */
    record Entry(String json, long computeMillis, long expiresAtMillis) {
    }

    private static final RedisScript<Long> RELEASE_LOCK = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final double beta;

    public StampedeProtectedCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters, double beta) {
        this.redis = redis;
        this.mapper = mapper;
        this.meters = meters;
        this.beta = beta;
    }

    public <T> T get(String key, Duration ttl, JavaType type, Supplier<T> loader) {
        Entry entry = read(key);
        if (entry != null && !shouldRefreshEarly(entry)) {
            return deserialize(entry, type);
        }
        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(acquired)) {
            try {
                return recompute(key, ttl, loader);
            } finally {
                // Delete only our own lock: it may have expired and been taken by someone else.
                redis.execute(RELEASE_LOCK, List.of(lockKey), token);
            }
        }
        if (entry != null) {
            return deserialize(entry, type); // someone else is refreshing; slightly stale is fine
        }
        return awaitOtherWriter(key, ttl, type, loader);
    }

    public void evict(String key) {
        redis.delete(key);
    }

    /** XFetch: refresh now if {@code now - delta * beta * ln(rand) >= expiry}. */
    boolean shouldRefreshEarly(Entry entry) {
        double rand = ThreadLocalRandom.current().nextDouble(Double.MIN_VALUE, 1.0);
        double gap = -entry.computeMillis() * beta * Math.log(rand);
        return System.currentTimeMillis() + gap >= entry.expiresAtMillis();
    }

    private <T> T recompute(String key, Duration ttl, Supplier<T> loader) {
        long start = System.nanoTime();
        T value = loader.get();
        long computeMillis = Math.max(1, (System.nanoTime() - start) / 1_000_000);
        meters.counter("catalog.cache.recomputes", "cache", key.substring(0, key.lastIndexOf(':'))).increment();

        long jitteredTtl = (long) (ttl.toMillis() * ThreadLocalRandom.current().nextDouble(0.9, 1.1));
        Entry entry = new Entry(write(value), computeMillis, System.currentTimeMillis() + jitteredTtl);
        redis.opsForValue().set(key, write(entry), Duration.ofMillis(jitteredTtl));
        return value;
    }

    /** Cold miss while another instance computes: poll briefly, then fail open. */
    private <T> T awaitOtherWriter(String key, Duration ttl, JavaType type, Supplier<T> loader) {
        for (int i = 0; i < 40; i++) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Entry entry = read(key);
            if (entry != null) {
                return deserialize(entry, type);
            }
        }
        return recompute(key, ttl, loader);
    }

    private Entry read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, Entry.class);
        } catch (Exception e) {
            return null; // unreadable entry: treat as a miss
        }
    }

    private <T> T deserialize(Entry entry, JavaType type) {
        try {
            return mapper.readValue(entry.json(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt cache entry", e);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
