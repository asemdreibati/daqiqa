package io.daqiqa.messaging.outbox;

import static io.daqiqa.messaging.outbox.OutboxFixtures.NOW;
import static io.daqiqa.messaging.outbox.OutboxFixtures.stockAdjusted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.daqiqa.contracts.events.StockAdjustedV1;
import io.daqiqa.contracts.events.Topics;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
class JdbcOutboxWriterTest {

    private final OutboxFixtures outbox = new OutboxFixtures();

    @BeforeEach
    void emptyOutbox() {
        outbox.clean();
    }

    @Test
    void refusesToAppendOutsideATransaction() {
        assertThatIllegalStateException()
                .isThrownBy(() -> outbox.writer.append(stockAdjusted("DXB-MARINA", 10)))
                .withMessageContaining("inside the transaction");

        assertThat(rowCount()).isZero();
    }

    @Test
    void storesTheEventAsJsonWithItsRoutingMetadata() {
        StockAdjustedV1 event = stockAdjusted("DXB-MARINA", 10);

        outbox.transaction.executeWithoutResult(status -> outbox.writer.append(event));

        StoredRow row = outbox.jdbc
                .sql("""
                        SELECT id, type, topic, partition_key, occurred_at, created_at, published_at, attempts
                        FROM messaging.outbox_event""")
                .query((rs, n) -> new StoredRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("type"),
                        rs.getString("topic"),
                        rs.getString("partition_key"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("published_at", OffsetDateTime.class),
                        rs.getInt("attempts")))
                .single();
        assertThat(row)
                .isEqualTo(new StoredRow(
                        event.eventId(),
                        StockAdjustedV1.TYPE,
                        Topics.INVENTORY,
                        "DXB-MARINA",
                        event.occurredAt(),
                        NOW,
                        null,
                        0));

        JsonMapper json = JsonMapper.builder().build();
        String payload = outbox.jdbc
                .sql("SELECT payload FROM messaging.outbox_event")
                .query(String.class)
                .single();
        assertThat(json.readTree(payload)).isEqualTo(json.readTree("""
                {"eventId": "%s", "occurredAt": "2026-09-22T11:59:55Z", "storeId": "DXB-MARINA",
                 "sku": "MILK-FULL-1L", "delta": 1, "reason": "RECEIVED", "onHandAfter": 10, "reservedAfter": 0}""".formatted(event.eventId())));
    }

    @Test
    void anEventRolledBackWithItsTransactionNeverReachesTheOutbox() {
        outbox.transaction.executeWithoutResult(status -> {
            outbox.writer.append(stockAdjusted("DXB-MARINA", 10));
            status.setRollbackOnly();
        });

        assertThat(rowCount()).isZero();
    }

    private long rowCount() {
        return outbox.jdbc
                .sql("SELECT count(*) FROM messaging.outbox_event")
                .query(Long.class)
                .single();
    }

    private record StoredRow(
            UUID id,
            String type,
            String topic,
            String partitionKey,
            Instant occurredAt,
            Instant createdAt,
            @Nullable OffsetDateTime publishedAt,
            int attempts) {}
}
