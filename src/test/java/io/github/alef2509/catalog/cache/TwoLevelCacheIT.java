package io.github.alef2509.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alef2509.catalog.IntegrationTest;
import io.github.alef2509.catalog.product.ProductService;
import io.github.alef2509.catalog.product.ProductView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TwoLevelCacheIT extends IntegrationTest {

    @Autowired
    private ProductService products;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private RedisConnectionFactory connectionFactory;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void secondReadIsServedWithoutTheDatabase() {
        UUID id = anyProduct("mice");
        double before = dbQueries("by-id");

        ProductView first = products.get(id);
        ProductView second = products.get(id);

        assertThat(second).isEqualTo(first);
        assertThat(dbQueries("by-id") - before).isEqualTo(1);
        assertThat(redis.hasKey("catalog:products::" + id)).isTrue();
    }

    @Test
    void localLevelAbsorbsReadsEvenWithoutRedis() {
        UUID id = anyProduct("audio");
        products.get(id);
        redis.delete("catalog:products::" + id); // L2 gone, L1 still warm
        double before = dbQueries("by-id");

        products.get(id);

        assertThat(dbQueries("by-id")).isEqualTo(before);
    }

    @Test
    void concurrentMissesLoadOnce() throws Exception {
        UUID id = anyProduct("video");
        double before = dbQueries("by-id");

        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            pool.invokeAll(IntStream.range(0, 50).<Callable<ProductView>>mapToObj(
                    i -> () -> products.get(id)).toList());
        }

        assertThat(dbQueries("by-id") - before).isEqualTo(1);
    }

    /**
     * A second "instance" (its own L1, same Redis) caches the product; a price
     * change on this instance must make it drop its now-stale local copy.
     */
    @Test
    void writesInvalidateTheLocalCacheOfOtherInstances() {
        UUID id = anyProduct("keyboards");
        TwoLevelCache otherInstance = secondInstanceCache();
        otherInstance.get(id, () -> products.get(id));
        assertThat(otherInstance.localSize()).isEqualTo(1);

        products.changePrice(id, new BigDecimal("123.45"));

        await().atMost(Duration.ofSeconds(5)).until(() -> otherInstance.localSize() == 0);
        assertThat(otherInstance.get(id, ProductView.class).price()).isEqualByComparingTo("123.45"); // refilled from Redis
    }

    @Test
    void writeThroughUpdatesTheCacheAfterCommit() {
        UUID id = anyProduct("monitors");
        products.get(id);

        products.changePrice(id, new BigDecimal("999.99"));
        double before = dbQueries("by-id");

        assertThat(products.get(id).price()).isEqualByComparingTo("999.99");
        assertThat(dbQueries("by-id")).isEqualTo(before); // served from the cache, already fresh
    }

    private TwoLevelCache secondInstanceCache() {
        CacheInvalidationBus bus = new CacheInvalidationBus(redis, mapper);
        RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connectionFactory);
        listener.addMessageListener(bus, new ChannelTopic(CacheInvalidationBus.CHANNEL));
        listener.afterPropertiesSet();
        listener.start();
        RedisCacheManager redisCaches = RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(CacheConfiguration.PRODUCTS, RedisCacheConfiguration.defaultCacheConfig()
                        .prefixCacheNameWith("catalog:")
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new Jackson2JsonRedisSerializer<>(mapper, ProductView.class))))
                .build();
        redisCaches.afterPropertiesSet();
        await().atMost(Duration.ofSeconds(5)).until(listener::isListening);
        return (TwoLevelCache) new TwoLevelCacheManager(redisCaches, bus, Duration.ofMinutes(1), 100)
                .getCache(CacheConfiguration.PRODUCTS);
    }
}
