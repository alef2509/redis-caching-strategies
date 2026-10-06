package io.github.alef2509.catalog.cache;

import io.github.alef2509.catalog.IntegrationTest;
import io.github.alef2509.catalog.product.ProductService;
import io.github.alef2509.catalog.product.ProductView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StampedeProtectionIT extends IntegrationTest {

    @Autowired
    private ProductService products;
    @Autowired
    private StampedeProtectedCache cache;

    /** 100 concurrent requests for a cold listing: one database query, not a hundred. */
    @Test
    void coldHotKeyIsComputedOnce() throws Exception {
        double before = dbQueries("by-category");

        List<Future<List<ProductView>>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
            results = pool.invokeAll(IntStream.range(0, 100)
                    .<Callable<List<ProductView>>>mapToObj(i -> () -> products.byCategory("keyboards")).toList());
        }

        assertThat(dbQueries("by-category") - before).isEqualTo(1);
        for (Future<List<ProductView>> result : results) {
            assertThat(result.get()).hasSize(100);
        }
    }

    @Test
    void entriesExpireWithJitter() {
        products.byCategory("mice");
        products.byCategory("audio");

        long miceTtl = redis.getExpire("catalog:category:mice");
        long audioTtl = redis.getExpire("catalog:category:audio");

        assertThat(miceTtl).isBetween(53L, 66L); // 60 s ± 10 %
        assertThat(audioTtl).isBetween(53L, 66L);
    }

    @Test
    void xfetchRefreshesEarlyOnlyNearExpiry() {
        long now = System.currentTimeMillis();
        StampedeProtectedCache.Entry fresh = new StampedeProtectedCache.Entry("[]", 50, now + 60_000);
        StampedeProtectedCache.Entry expiring = new StampedeProtectedCache.Entry("[]", 50, now + 5);

        long earlyForFresh = IntStream.range(0, 1000).filter(i -> cache.shouldRefreshEarly(fresh)).count();
        long earlyForExpiring = IntStream.range(0, 1000).filter(i -> cache.shouldRefreshEarly(expiring)).count();

        assertThat(earlyForFresh).isZero();
        assertThat(earlyForExpiring).isGreaterThan(800);
    }

    @Test
    void priceChangeEvictsTheAffectedListing() {
        products.byCategory("video");
        assertThat(redis.hasKey("catalog:category:video")).isTrue();

        products.changePrice(anyProduct("video"), new BigDecimal("42.00"));

        assertThat(redis.hasKey("catalog:category:video")).isFalse();
    }
}
