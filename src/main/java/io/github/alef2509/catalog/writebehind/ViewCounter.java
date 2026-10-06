package io.github.alef2509.catalog.writebehind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Write-behind: product views are counted in Redis ({@code HINCRBY}, ~0.1 ms, no
 * row lock on a hot product) and flushed to PostgreSQL in batches.
 *
 * <p>A flush atomically renames the pending hash to a uniquely named batch, so new
 * views keep accumulating in a fresh hash meanwhile. The batch is applied in one
 * transaction that also records its id in {@code view_flushes}: if the process
 * dies after the commit but before deleting the batch, the next run sees the id
 * and only deletes it. Every view is counted exactly once.
 */
@Component
public class ViewCounter {

    static final String PENDING = "catalog:views:pending";
    static final String BATCH_PREFIX = "catalog:views:batch:";

    /** RENAME fails on a missing key; this does nothing instead. */
    private static final RedisScript<Long> CUT_BATCH = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                redis.call('RENAME', KEYS[1], KEYS[2])
                return 1
            end
            return 0
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(ViewCounter.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ViewCounter(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void recordView(UUID productId) {
        redis.opsForHash().increment(PENDING, productId.toString(), 1);
    }

    public long pendingViews(UUID productId) {
        long pending = 0;
        for (String key : keys()) {
            Object value = redis.opsForHash().get(key, productId.toString());
            pending += value == null ? 0 : Long.parseLong(value.toString());
        }
        return pending;
    }

    @Scheduled(fixedDelayString = "${catalog.views.flush-interval:5s}")
    public void flush() {
        String batchKey = BATCH_PREFIX + UUID.randomUUID();
        redis.execute(CUT_BATCH, List.of(PENDING, batchKey));
        for (String key : batchKeys()) { // includes leftovers of a crashed run
            apply(key);
        }
    }

    private void apply(String batchKey) {
        Map<Object, Object> counts = redis.opsForHash().entries(batchKey);
        String batchId = batchKey.substring(BATCH_PREFIX.length());
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO view_flushes (batch_id, applied_at) VALUES (?, ?)", batchId,
                        Timestamp.from(clock.instant()));
                List<Object[]> rows = new ArrayList<>();
                counts.forEach((id, n) -> rows.add(new Object[]{Long.parseLong(n.toString()), UUID.fromString(id.toString())}));
                jdbc.batchUpdate("UPDATE products SET views = views + ? WHERE id = ?", rows);
            });
            log.debug("Flushed {} product view counters", counts.size());
        } catch (DuplicateKeyException alreadyApplied) {
            log.info("Batch {} was already applied, discarding it", batchId);
        }
        redis.delete(batchKey);
    }

    private List<String> keys() {
        List<String> keys = new ArrayList<>(batchKeys());
        keys.add(PENDING);
        return keys;
    }

    private List<String> batchKeys() {
        List<String> keys = new ArrayList<>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(BATCH_PREFIX + "*").count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }
}
