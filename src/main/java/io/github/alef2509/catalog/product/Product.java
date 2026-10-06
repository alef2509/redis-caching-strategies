package io.github.alef2509.catalog.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id
    private UUID id;
    private String sku;
    private String name;
    private String category;
    private BigDecimal price;
    private long views;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    private long version;

    protected Product() {
    }

    void changePrice(BigDecimal newPrice, Instant now) {
        this.price = newPrice;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getViews() {
        return views;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
