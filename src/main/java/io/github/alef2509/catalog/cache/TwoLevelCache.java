package io.github.alef2509.catalog.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.Callable;

/**
 * Near cache: an in-process Caffeine cache (L1, nanoseconds, per instance) in
 * front of Redis (L2, ~1 ms, shared by all instances).
 *
 * <p>Reads try L1, then L2 (filling L1), then the loader (filling both). Writes and
 * evictions go to both levels and are broadcast so the <em>other</em> instances drop
 * their L1 copy; without that, they would serve stale data until their L1 expired.
 * L1 also has a short TTL as a safety net for a lost invalidation message.
 */
public class TwoLevelCache implements Cache {

    private final String name;
    private final com.github.benmanes.caffeine.cache.Cache<Object, Object> local;
    private final Cache remote;
    private final CacheInvalidationBus bus;

    public TwoLevelCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> local, Cache remote,
                         CacheInvalidationBus bus) {
        this.name = name;
        this.local = local;
        this.remote = remote;
        this.bus = bus;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
        Object value = local.getIfPresent(key);
        if (value != null) {
            return new SimpleValueWrapper(value);
        }
        ValueWrapper fromRedis = remote.get(key);
        if (fromRedis != null && fromRedis.get() != null) {
            local.put(key, fromRedis.get());
        }
        return fromRedis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        return wrapper == null ? null : (T) wrapper.get();
    }

    /**
     * Used by {@code @Cacheable(sync = true)}: concurrent misses for the same key in
     * this instance wait for a single load (Caffeine computes once per key).
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        return (T) local.get(key, k -> {
            ValueWrapper fromRedis = remote.get(k);
            if (fromRedis != null && fromRedis.get() != null) {
                return fromRedis.get();
            }
            try {
                T loaded = valueLoader.call();
                remote.put(k, loaded);
                return loaded;
            } catch (Exception e) {
                throw new ValueRetrievalException(k, valueLoader, e);
            }
        });
    }

    @Override
    public void put(Object key, Object value) {
        remote.put(key, value);
        local.put(key, value);
        bus.publishEviction(name, key);
    }

    @Override
    public void evict(Object key) {
        remote.evict(key);
        local.invalidate(key);
        bus.publishEviction(name, key);
    }

    @Override
    public void clear() {
        remote.clear();
        local.invalidateAll();
        bus.publishClear(name);
    }

    /** Called when another instance changed {@code key}: drop only the local copy. */
    void evictLocal(Object key) {
        local.invalidate(key);
    }

    void clearLocal() {
        local.invalidateAll();
    }

    long localSize() {
        local.cleanUp();
        return local.estimatedSize();
    }
}
