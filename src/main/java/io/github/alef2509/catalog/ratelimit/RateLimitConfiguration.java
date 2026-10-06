package io.github.alef2509.catalog.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class RateLimitConfiguration {

    @Bean
    RateLimiter rateLimiter(StringRedisTemplate redis,
                            @Value("${catalog.rate-limit.capacity:20}") int capacity,
                            @Value("${catalog.rate-limit.refill-per-second:10}") double refillPerSecond) {
        return new RateLimiter(redis, capacity, refillPerSecond);
    }

    @Bean
    WebMvcConfigurer rateLimiting(RateLimiter limiter) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RateLimitInterceptor(limiter)).addPathPatterns("/api/**");
            }
        };
    }
}
