package io.daqiqa.messaging.outbox;

import io.daqiqa.contracts.events.StockAdjustedV1;
import io.daqiqa.kernel.Ids;
import io.daqiqa.messaging.outbox.MessagingProperties.Kafka;
import io.daqiqa.messaging.outbox.MessagingProperties.Publisher;
import io.daqiqa.messaging.outbox.MessagingProperties.Purge;
import io.daqiqa.messaging.outbox.MessagingProperties.Relay;
import io.daqiqa.testing.postgres.PostgresTestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A migrated outbox on real PostgreSQL, plus the plain objects the outbox tests wire by hand. */
final class OutboxFixtures {

    static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    final PostgresTestDatabase db = PostgresTestDatabase.shared().migrate("classpath:db/migration/messaging");
    final JdbcClient jdbc = JdbcClient.create(db.dataSource());
    final PlatformTransactionManager transactionManager = new DataSourceTransactionManager(db.dataSource());
    final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    final OutboxRepository repository = new OutboxRepository(jdbc);
    final JdbcOutboxWriter writer = new JdbcOutboxWriter(repository, CLOCK);

    void clean() {
        db.truncateSchemas("messaging");
    }

    /** Appends each event in its own committed transaction, like independent business transactions would. */
    void appendCommitted(List<StockAdjustedV1> events) {
        events.forEach(event -> transaction.executeWithoutResult(status -> writer.append(event)));
    }

    /** Ids of the unpublished events, in publication order. */
    List<UUID> unpublishedIds() {
        return jdbc.sql("SELECT id FROM messaging.outbox_event WHERE published_at IS NULL ORDER BY seq")
                .query(UUID.class)
                .list();
    }

    static StockAdjustedV1 stockAdjusted(String storeId, int onHandAfter) {
        return new StockAdjustedV1(
                Ids.newTimeOrderedId(), NOW.minusSeconds(5), storeId, "MILK-FULL-1L", 1, "RECEIVED", onHandAfter, 0);
    }

    static MessagingProperties properties(int relayBatchSize, int purgeBatchSize) {
        return properties(relayBatchSize, purgeBatchSize, Duration.ofMinutes(5));
    }

    static MessagingProperties properties(int relayBatchSize, int purgeBatchSize, Duration maxBackoff) {
        return properties(relayBatchSize, purgeBatchSize, maxBackoff, Duration.ofSeconds(2));
    }

    static MessagingProperties properties(
            int relayBatchSize, int purgeBatchSize, Duration maxBackoff, Duration maxBatchDuration) {
        return new MessagingProperties(
                Publisher.LOGGING,
                new Relay(Duration.ofMillis(250), relayBatchSize, maxBatchDuration, Duration.ofSeconds(1), maxBackoff),
                Duration.ofDays(7),
                new Purge(Duration.ofHours(1), purgeBatchSize),
                new Kafka(Duration.ofSeconds(10)));
    }
}
