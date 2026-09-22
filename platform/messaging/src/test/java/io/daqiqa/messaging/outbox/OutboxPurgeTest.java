package io.daqiqa.messaging.outbox;

import static io.daqiqa.messaging.outbox.OutboxFixtures.NOW;
import static io.daqiqa.messaging.outbox.OutboxFixtures.properties;
import static io.daqiqa.messaging.outbox.OutboxFixtures.stockAdjusted;
import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.contracts.events.StockAdjustedV1;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class OutboxPurgeTest {

    private final OutboxFixtures outbox = new OutboxFixtures();

    @BeforeEach
    void emptyOutbox() {
        outbox.clean();
    }

    @Test
    void deletesOnlyEventsPublishedLongerAgoThanTheRetention() {
        StockAdjustedV1 old1 = append(NOW.minus(Duration.ofDays(9)));
        StockAdjustedV1 old2 = append(NOW.minus(Duration.ofDays(8)));
        StockAdjustedV1 old3 = append(NOW.minus(Duration.ofDays(7)).minusSeconds(1));
        StockAdjustedV1 recent = append(NOW.minus(Duration.ofDays(6)));
        StockAdjustedV1 neverPublished = append(null);

        // A batch of two forces the purge to loop until nothing is left to delete.
        int deleted = new OutboxPurge(outbox.repository, OutboxFixtures.CLOCK, properties(100, 2)).purge();

        assertThat(deleted).isEqualTo(3);
        assertThat(remainingIds())
                .containsExactlyInAnyOrder(recent.eventId(), neverPublished.eventId())
                .doesNotContain(old1.eventId(), old2.eventId(), old3.eventId());
    }

    private StockAdjustedV1 append(@Nullable Instant publishedAt) {
        StockAdjustedV1 event = stockAdjusted("DXB-MARINA", 1);
        outbox.appendCommitted(List.of(event));
        if (publishedAt != null) {
            outbox.jdbc
                    .sql("UPDATE messaging.outbox_event SET published_at = :at WHERE id = :id")
                    .param("at", publishedAt.atOffset(ZoneOffset.UTC))
                    .param("id", event.eventId())
                    .update();
        }
        return event;
    }

    private List<UUID> remainingIds() {
        return outbox.jdbc
                .sql("SELECT id FROM messaging.outbox_event")
                .query(UUID.class)
                .list();
    }
}
