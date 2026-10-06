package io.github.alef2509.catalog.writebehind;

import io.github.alef2509.catalog.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class WriteBehindIT extends IntegrationTest {

    @Autowired
    private ViewCounter views;

    @Test
    void viewsAreCountedInRedisThenFlushedToTheDatabase() throws Exception {
        UUID id = anyProduct("keyboards");
        long before = persisted(id);

        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            IntStream.range(0, 200).forEach(i -> pool.submit(() -> views.recordView(id)));
        }
        assertThat(views.pendingViews(id)).isEqualTo(200);
        assertThat(persisted(id)).isEqualTo(before);

        views.flush();

        assertThat(persisted(id)).isEqualTo(before + 200);
        assertThat(views.pendingViews(id)).isZero();
    }

    @Test
    void viewsArrivingDuringAFlushGoToTheNextBatch() {
        UUID id = anyProduct("mice");
        long before = persisted(id);
        views.recordView(id);
        views.flush();
        views.recordView(id);

        assertThat(persisted(id)).isEqualTo(before + 1);
        assertThat(views.pendingViews(id)).isEqualTo(1);
    }

    /** Crash after the database commit, before deleting the batch: no double counting. */
    @Test
    void reappliedBatchesAreIgnored() {
        UUID id = anyProduct("audio");
        long before = persisted(id);
        String batchId = UUID.randomUUID().toString();
        redis.opsForHash().put(ViewCounter.BATCH_PREFIX + batchId, id.toString(), "7");
        views.flush(); // applies the leftover batch
        redis.opsForHash().put(ViewCounter.BATCH_PREFIX + batchId, id.toString(), "7"); // same batch again

        views.flush();

        assertThat(persisted(id)).isEqualTo(before + 7);
        assertThat(redis.keys(ViewCounter.BATCH_PREFIX + "*")).isEmpty();
    }

    private long persisted(UUID id) {
        return jdbc.queryForObject("SELECT views FROM products WHERE id = ?", Long.class, id);
    }
}
