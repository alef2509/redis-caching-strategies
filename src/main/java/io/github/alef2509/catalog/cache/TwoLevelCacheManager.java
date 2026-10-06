package io.github.alef2509.catalog.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TwoLevelCacheManager implements CacheManager {

    private final RedisCacheManager redis;
    private final CacheInvalidationBus bus;
    private final Duration localTtl;
    private final long localMaxSize;
    private final Map<String, TwoLevelCache> caches = new ConcurrentHashMap<>();

    public TwoLevelCacheManager(RedisCacheManager redis, CacheInvalidationBus bus, Duration localTtl,
                                long localMaxSize) {
        this.redis = redis;
        this.bus = bus;
        this.localTtl = localTtl;
        this.localMaxSize = localMaxSize;
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, n -> {
            TwoLevelCache cache = new TwoLevelCache(n,
                    Caffeine.newBuilder().expireAfterWrite(localTtl).maximumSize(localMaxSize).build(),
                    redis.getCache(n), bus);
            bus.register(cache);
            return cache;
        });
    }

    @Override
    public Collection<String> getCacheNames() {
        return caches.keySet();
    }
}
