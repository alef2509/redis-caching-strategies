package io.github.alef2509.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByCategoryOrderByName(String category);

    @Modifying
    @Query("update Product p set p.views = p.views + :delta where p.id = :id")
    int addViews(UUID id, long delta);
}
