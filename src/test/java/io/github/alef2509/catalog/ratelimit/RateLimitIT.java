package io.github.alef2509.catalog.ratelimit;

import io.github.alef2509.catalog.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestPropertySource(properties = {"catalog.rate-limit.capacity=5", "catalog.rate-limit.refill-per-second=2"})
class RateLimitIT extends IntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private RateLimiter limiter;

    @Test
    void burstIsAllowedThenThrottledWithStandardHeaders() {
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 5; i++) {
            assertThat(call(key).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> throttled = call(key);

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst("RateLimit-Limit")).isEqualTo("5");
        assertThat(throttled.getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
        assertThat(throttled.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void clientsHaveIndependentBuckets() {
        String noisy = UUID.randomUUID().toString();
        IntStream.range(0, 10).forEach(i -> call(noisy));

        assertThat(call(UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tokensRefillOverTime() {
        String key = UUID.randomUUID().toString();
        IntStream.range(0, 6).forEach(i -> call(key));

        await().atMost(Duration.ofSeconds(3)).until(() -> call(key).getStatusCode() == HttpStatus.OK);
    }

    /** 50 concurrent requests, capacity 5: the Lua script lets exactly 5 through. */
    @Test
    void concurrentRequestsCannotOverdrawTheBucket() throws Exception {
        String client = "concurrency-" + UUID.randomUUID();

        long allowed = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(25)) {
            for (Future<Boolean> f : pool.invokeAll(IntStream.range(0, 50)
                    .<Callable<Boolean>>mapToObj(i -> () -> limiter.tryAcquire(client).allowed()).toList())) {
                allowed += f.get() ? 1 : 0;
            }
        }

        assertThat(allowed).isBetween(5L, 6L); // 5, or 6 if a token refilled (2/s) during the burst
    }

    private ResponseEntity<String> call(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", apiKey);
        return rest.exchange("/api/v1/products?category=mice", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
