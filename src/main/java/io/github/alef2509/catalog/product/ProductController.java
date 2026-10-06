package io.github.alef2509.catalog.product;

import io.github.alef2509.catalog.writebehind.ViewCounter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
class ProductController {

    record PriceChange(@NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal price) {
    }

    record ViewStats(UUID productId, long persistedViews, long pendingViews) {
    }

    private final ProductService products;
    private final ViewCounter views;
    private final JdbcTemplate jdbc;

    ProductController(ProductService products, ViewCounter views, JdbcTemplate jdbc) {
        this.products = products;
        this.views = views;
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}")
    ProductView get(@PathVariable UUID id) {
        return products.get(id);
    }

    @GetMapping
    List<ProductView> byCategory(@RequestParam String category) {
        return products.byCategory(category);
    }

    @PutMapping("/{id}/price")
    ProductView changePrice(@PathVariable UUID id, @Valid @RequestBody PriceChange change) {
        return products.changePrice(id, change.price());
    }

    /** Fire-and-forget counter: 202, the view is persisted by the next flush. */
    @PostMapping("/{id}/views")
    ResponseEntity<Void> view(@PathVariable UUID id) {
        views.recordView(id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/stats")
    ViewStats stats(@PathVariable UUID id) {
        Long persisted = jdbc.query("SELECT views FROM products WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, id);
        if (persisted == null) {
            throw new ProductNotFoundException(id);
        }
        return new ViewStats(id, persisted, views.pendingViews(id));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail notFound(ProductNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
