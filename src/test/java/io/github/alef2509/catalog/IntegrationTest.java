package io.github.alef2509.catalog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
public abstract class IntegrationTest {

    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MeterRegistry meters;

    @BeforeEach
    void flushRedis() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    protected double dbQueries(String query) {
        Counter counter = meters.find("catalog.db.queries").tag("query", query).counter();
        return counter == null ? 0 : counter.count();
    }

    protected UUID anyProduct(String category) {
        return jdbc.queryForObject("SELECT id FROM products WHERE category = ? ORDER BY random() LIMIT 1",
                UUID.class, category);
    }
}
