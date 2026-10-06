package io.github.alef2509.catalog.product;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.alef2509.catalog.cache.CacheConfiguration;
import io.github.alef2509.catalog.cache.StampedeProtectedCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductQueries queries;
    private final ProductRepository products;
    private final StampedeProtectedCache listings;
    private final Cache productCache;
    private final Clock clock;
    private final Duration listingTtl;
    private final JavaType listingType;

    ProductService(ProductQueries queries, ProductRepository products, StampedeProtectedCache listings,
                   CacheManager cacheManager, Clock clock, ObjectMapper mapper,
                   @Value("${catalog.cache.listing-ttl:60s}") Duration listingTtl) {
        this.queries = queries;
        this.products = products;
        this.listings = listings;
        this.productCache = cacheManager.getCache(CacheConfiguration.PRODUCTS);
        this.clock = clock;
        this.listingTtl = listingTtl;
        this.listingType = mapper.getTypeFactory().constructCollectionType(List.class, ProductView.class);
    }

    /** Cache-aside through the two-level cache; concurrent misses in one instance load once. */
    @Cacheable(cacheNames = CacheConfiguration.PRODUCTS, key = "#id", sync = true)
    public ProductView get(UUID id) {
        return queries.byId(id);
    }

    /** Hot listing behind the stampede-protected cache. */
    public List<ProductView> byCategory(String category) {
        return listings.get(listingKey(category), listingTtl, listingType, () -> queries.byCategory(category));
    }

    /**
     * Write-through: the database is updated first; only <em>after the commit</em> is
     * the new value written to the cache and the affected listing evicted. Writing
     * the cache inside the transaction could publish a price that is then rolled back.
     */
    @Transactional
    public ProductView changePrice(UUID id, BigDecimal price) {
        Product product = products.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
        product.changePrice(price, clock.instant());
        products.flush();
        ProductView updated = ProductView.from(product);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productCache.put(id, updated);
                listings.evict(listingKey(updated.category()));
            }
        });
        return updated;
    }

    static String listingKey(String category) {
        return "catalog:category:" + category;
    }
}
