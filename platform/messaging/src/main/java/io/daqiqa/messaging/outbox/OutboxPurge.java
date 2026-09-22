package io.daqiqa.messaging.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes published events once they are older than {@code daqiqa.messaging.retention}, so the outbox stays small
 * (ADR-0009). Unpublished events are never deleted, however old. Several instances may purge at the same time: they
 * delete disjoint or already-deleted rows, which is harmless.
 */
@Component
class OutboxPurge {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurge.class);

    private final OutboxRepository repository;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    OutboxPurge(OutboxRepository repository, Clock clock, MessagingProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.retention = properties.retention();
        this.batchSize = properties.purge().batchSize();
    }

    /** Deletes every event published before now minus the retention, in batches; returns how many were deleted. */
    @Scheduled(
            fixedDelayString = "${daqiqa.messaging.purge.interval:" + MessagingProperties.Purge.DEFAULT_INTERVAL + "}")
    public int purge() {
        Instant cutoff = clock.instant().minus(retention);
        int deleted = 0;
        int batch;
        do {
            batch = repository.deletePublishedBefore(cutoff, batchSize);
            deleted += batch;
        } while (batch == batchSize);
        if (deleted > 0) {
            log.info("Purged {} outbox events published before {}", deleted, cutoff);
        }
        return deleted;
    }
}
