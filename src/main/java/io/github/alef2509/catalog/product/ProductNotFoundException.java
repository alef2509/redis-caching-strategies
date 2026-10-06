package io.github.alef2509.catalog.product;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(UUID id) {
        super("Product %s not found".formatted(id));
    }
}
