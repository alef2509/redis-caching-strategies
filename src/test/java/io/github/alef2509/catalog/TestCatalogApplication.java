package io.github.alef2509.catalog;

import org.springframework.boot.SpringApplication;

/** {@code ./mvnw spring-boot:test-run}: the app with throwaway PostgreSQL and Redis. */
public class TestCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.from(CatalogApplication::main).with(TestInfrastructure.class).run(args);
    }
}
