package io.github.alef2509.catalog.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Pub/Sub channel through which instances tell each other to drop L1
 * entries. Pub/Sub is fire-and-forget (an instance that is disconnected misses
 * messages), which is acceptable here because L1 entries also expire quickly.
 */
public class CacheInvalidationBus implements MessageListener {

    public static final String CHANNEL = "catalog:cache-invalidation";

    record Invalidation(String origin, String cache, String key) {
    }

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationBus.class);

    private final String instanceId = UUID.randomUUID().toString();
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Map<String, TwoLevelCache> caches = new ConcurrentHashMap<>();

    public CacheInvalidationBus(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    void register(TwoLevelCache cache) {
        caches.put(cache.getName(), cache);
    }

    void publishEviction(String cache, Object key) {
        publish(new Invalidation(instanceId, cache, key.toString()));
    }

    void publishClear(String cache) {
        publish(new Invalidation(instanceId, cache, null));
    }

    private void publish(Invalidation invalidation) {
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(invalidation));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Invalidation invalidation = mapper.readValue(message.getBody(), Invalidation.class);
            if (instanceId.equals(invalidation.origin())) {
                return; // our own change, already applied locally
            }
            TwoLevelCache cache = caches.get(invalidation.cache());
            if (cache == null) {
                return;
            }
            if (invalidation.key() == null) {
                cache.clearLocal();
            } else {
                cache.evictLocal(keyOf(invalidation.key()));
            }
        } catch (Exception e) {
            log.warn("Ignoring malformed invalidation message: {}", e.getMessage());
        }
    }

    /** Keys of the two-level caches are UUIDs (product ids). */
    private static Object keyOf(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return key;
        }
    }

    String instanceId() {
        return instanceId;
    }
}
