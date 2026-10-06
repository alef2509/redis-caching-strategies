package io.github.alef2509.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alef2509.catalog.product.ProductView;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {

    public static final String PRODUCTS = "products";

    @Bean
    CacheInvalidationBus cacheInvalidationBus(StringRedisTemplate redis, ObjectMapper mapper) {
        return new CacheInvalidationBus(redis, mapper);
    }

    @Bean
    RedisMessageListenerContainer cacheInvalidationListener(RedisConnectionFactory connectionFactory,
                                                            CacheInvalidationBus bus) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bus, new ChannelTopic(CacheInvalidationBus.CHANNEL));
        return container;
    }

    /**
     * Values are stored as plain JSON of a known type, not with embedded class
     * names: no polymorphic deserialization of data read back from Redis.
     */
    @Bean
    CacheManager cacheManager(RedisConnectionFactory connectionFactory, CacheInvalidationBus bus,
                              ObjectMapper mapper,
                              @Value("${catalog.cache.products.redis-ttl:10m}") Duration redisTtl,
                              @Value("${catalog.cache.products.local-ttl:30s}") Duration localTtl) {
        RedisCacheConfiguration products = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(redisTtl)
                .prefixCacheNameWith("catalog:")
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(mapper, ProductView.class)));
        RedisCacheManager redis = RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(PRODUCTS, products)
                .build();
        redis.afterPropertiesSet();
        return new TwoLevelCacheManager(redis, bus, localTtl, 10_000);
    }

    @Bean
    StampedeProtectedCache stampedeProtectedCache(StringRedisTemplate redis, ObjectMapper mapper,
                                                  MeterRegistry meters,
                                                  @Value("${catalog.cache.xfetch-beta:1.0}") double beta) {
        return new StampedeProtectedCache(redis, mapper, meters, beta);
    }
}
