package io.github.alef2509.catalog.product;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Database reads behind the caches. A configurable delay stands in for a heavy
 * query (joins, a remote database, a slow third party), so the effect of each
 * cache is visible; every call is counted in {@code catalog.db.queries}.
 */
@Component
class ProductQueries {

    private final ProductRepository products;
    private final MeterRegistry meters;
    private final Duration simulatedLatency;

    ProductQueries(ProductRepository products, MeterRegistry meters,
                   @Value("${catalog.simulated-db-latency:50ms}") Duration simulatedLatency) {
        this.products = products;
        this.meters = meters;
        this.simulatedLatency = simulatedLatency;
    }

    @Transactional(readOnly = true)
    ProductView byId(UUID id) {
        hit("by-id");
        return products.findById(id).map(ProductView::from).orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    List<ProductView> byCategory(String category) {
        hit("by-category");
        return products.findByCategoryOrderByName(category).stream().map(ProductView::from).toList();
    }

    private void hit(String query) {
        meters.counter("catalog.db.queries", "query", query).increment();
        try {
            Thread.sleep(simulatedLatency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
