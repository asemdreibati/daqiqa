package io.daqiqa.messaging.outbox;

import static io.daqiqa.messaging.outbox.OutboxFixtures.NOW;
import static io.daqiqa.messaging.outbox.OutboxFixtures.stockAdjusted;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The relay's statements run every quarter of a second against a table that holds days of published events, so each
 * must be able to use the partial index on unpublished rows (measured on 300k rows: 0.6 ms instead of 30 ms). With
 * sequential scans disabled, a statement whose predicate cannot match the index still plans a sequential scan, which
 * is what these tests catch.
 */
@Tag("integration")
class OutboxQueryPlanTest {

    private static final String INDEX = "outbox_event_unpublished_idx";

    private final OutboxFixtures outbox = new OutboxFixtures();

    @BeforeEach
    void someEvents() {
        outbox.clean();
        outbox.appendCommitted(IntStream.range(0, 20)
                .mapToObj(i -> stockAdjusted("DXB-MARINA", i))
                .toList());
    }

    @Test
    void theRelayFindsItsNextBatchThroughThePartialIndex() {
        assertThat(plan(OutboxRepository.LOCK_NEXT_BATCH, statement -> statement.param("limit", 100)))
                .contains(INDEX);
    }

    @Test
    void markingEventsPublishedGoesThroughThePartialIndex() {
        assertThat(plan(
                        OutboxRepository.MARK_PUBLISHED,
                        statement -> statement
                                .param("publishedAt", NOW.atOffset(ZoneOffset.UTC))
                                .param("seqs", new long[] {1, 2, 3})))
                .contains(INDEX);
    }

    @Test
    void theBacklogIsCountedFromThePartialIndex() {
        assertThat(plan(OutboxRepository.COUNT_UNPUBLISHED, statement -> statement))
                .contains(INDEX);
    }

    private String plan(String sql, UnaryOperator<JdbcClient.StatementSpec> parameters) {
        List<String> lines = outbox.transaction.execute(status -> {
            outbox.jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return parameters
                    .apply(outbox.jdbc.sql("EXPLAIN " + sql))
                    .query(String.class)
                    .list();
        });
        return String.join("\n", Objects.requireNonNull(lines));
    }
}
