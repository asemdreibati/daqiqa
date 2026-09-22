package io.daqiqa.ordering.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes idempotency keys once they expire ({@code daqiqa.ordering.idempotency.retention} after their first use), in
 * bounded batches so that a large purge is a series of short statements.
 */
@Component
class IdempotencyKeyPurge {

    private final IdempotencyKeyRepository keys;
    private final Clock clock;
    private final int batchSize;

    IdempotencyKeyPurge(IdempotencyKeyRepository keys, Clock clock, OrderingProperties properties) {
        this.keys = keys;
        this.clock = clock;
        this.batchSize = properties.idempotency().purgeBatchSize();
    }

    /** Deletes every key expired by now; returns how many. */
    @Scheduled(
            fixedDelayString = "${daqiqa.ordering.idempotency.purge-interval:"
                    + OrderingProperties.Idempotency.DEFAULT_PURGE_INTERVAL + "}")
    public int purge() {
        Instant now = clock.instant();
        int deleted = 0;
        int batch;
        do {
            batch = keys.deleteExpired(now, batchSize);
            deleted += batch;
        } while (batch == batchSize);
        return deleted;
    }
}
