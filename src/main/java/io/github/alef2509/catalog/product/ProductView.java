package io.github.alef2509.catalog.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable, cache-friendly projection of a product (no JPA proxies in the cache). */
public record ProductView(UUID id, String sku, String name, String category, BigDecimal price, Instant updatedAt) {

    static ProductView from(Product p) {
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getPrice(), p.getUpdatedAt());
    }
}
