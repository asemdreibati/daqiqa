package io.daqiqa.messaging.outbox;

import static io.daqiqa.messaging.outbox.OutboxFixtures.NOW;
import static io.daqiqa.messaging.outbox.OutboxFixtures.properties;
import static io.daqiqa.messaging.outbox.OutboxFixtures.stockAdjusted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.contracts.events.IntegrationEvent;
import io.daqiqa.contracts.events.StockAdjustedV1;
import io.daqiqa.messaging.outbox.RecordingPublisher.SimulatedCrash;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class OutboxRelayTest {

    private final OutboxFixtures outbox = new OutboxFixtures();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void emptyOutbox() {
        outbox.clean();
    }

    @Test
    void publishesCommittedEventsInAppendOrderAndMarksThemPublished() {
        List<StockAdjustedV1> events =
                List.of(stockAdjusted("DXB-MARINA", 1), stockAdjusted("DXB-JLT", 2), stockAdjusted("DXB-MARINA", 3));
        outbox.appendCommitted(events);

        RelayRound round = relay(10).relay();

        assertThat(round).isEqualTo(new RelayRound(true, 3, false));
        assertThat(publisher.published()).containsExactlyElementsOf(ids(events));
        assertThat(outbox.unpublishedIds()).isEmpty();
        assertThat(outbox.jdbc
                        .sql("SELECT DISTINCT published_at FROM messaging.outbox_event")
                        .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                        .list())
                .containsExactly(NOW);
        assertThat(relay(10).relay()).isEqualTo(new RelayRound(true, 0, false));
        assertThat(publisher.published()).hasSize(3);
    }

    @Test
    void publishesAtMostOneBatchPerRound() {
        List<StockAdjustedV1> events = IntStream.range(0, 5)
                .mapToObj(i -> stockAdjusted("DXB-MARINA", i))
                .toList();
        outbox.appendCommitted(events);
        OutboxRelay relay = relay(2);

        assertThat(List.of(
                        relay.relay().published(),
                        relay.relay().published(),
                        relay.relay().published()))
                .containsExactly(2, 2, 1);
        assertThat(publisher.published()).containsExactlyElementsOf(ids(events));
    }

    @Test
    void stopsTheRoundAtTheFirstFailureSoThatLaterEventsCannotOvertakeIt() {
        List<StockAdjustedV1> events =
                List.of(stockAdjusted("DXB-MARINA", 1), stockAdjusted("DXB-MARINA", 2), stockAdjusted("DXB-MARINA", 3));
        outbox.appendCommitted(events);
        UUID second = events.get(1).eventId();
        publisher.failWhen(event -> event.id().equals(second));
        OutboxRelay relay = relay(10);

        RelayRound failed = relay.relay();

        assertThat(failed).isEqualTo(new RelayRound(true, 1, true));
        assertThat(publisher.published()).containsExactly(events.getFirst().eventId());
        assertThat(outbox.unpublishedIds())
                .containsExactly(second, events.get(2).eventId());
        assertThat(outbox.jdbc
                        .sql("SELECT attempts, last_error FROM messaging.outbox_event WHERE id = :id")
                        .param("id", second)
                        .query((rs, n) -> rs.getInt("attempts") + " " + rs.getString("last_error"))
                        .single())
                .startsWith("1 ")
                .contains("broker unavailable");
        assertThat(outbox.jdbc
                        .sql("SELECT attempts FROM messaging.outbox_event WHERE id = :id")
                        .param("id", events.get(2).eventId())
                        .query(Integer.class)
                        .single())
                .as("the event after the failure was not even tried")
                .isZero();

        publisher.heal();
        assertThat(relayAt(NOW.plusSeconds(1)).relay()).isEqualTo(new RelayRound(true, 2, false));
        assertThat(publisher.published()).containsExactlyElementsOf(ids(events));
    }

    @Test
    void waitsOutTheBackoffBeforeRetryingAFailedEventAndLetsNothingOvertakeIt() {
        List<StockAdjustedV1> events = List.of(stockAdjusted("DXB-MARINA", 1), stockAdjusted("DXB-JLT", 2));
        outbox.appendCommitted(events);
        UUID first = events.getFirst().eventId();
        publisher.failWhen(event -> event.id().equals(first));
        relay(10).relay();
        publisher.heal();

        assertThat(nextAttemptAt(first)).isEqualTo(NOW.plusSeconds(1));
        assertThat(relayAt(NOW.plusMillis(999)).relay()).isEqualTo(new RelayRound(true, 0, false));
        assertThat(publisher.published())
                .as("the failed event waits, and so does everything after it")
                .isEmpty();

        assertThat(relayAt(NOW.plusSeconds(1)).relay()).isEqualTo(new RelayRound(true, 2, false));
        assertThat(publisher.published()).containsExactlyElementsOf(ids(events));
    }

    @Test
    void doublesTheBackoffAfterEveryFailureUpToTheMaximum() {
        StockAdjustedV1 poison = stockAdjusted("DXB-MARINA", 1);
        outbox.appendCommitted(List.of(poison));
        publisher.failWhen(event -> true);
        List<Duration> waits = new ArrayList<>();
        Instant attemptAt = NOW;

        for (int attempt = 1; attempt <= 5; attempt++) {
            relayAt(attemptAt, Duration.ofSeconds(5)).relay();
            Instant next = nextAttemptAt(poison.eventId());
            waits.add(Duration.between(attemptAt, next));
            attemptAt = next;
        }

        assertThat(waits)
                .containsExactly(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5));
        assertThat(outbox.jdbc
                        .sql("SELECT attempts FROM messaging.outbox_event")
                        .query(Integer.class)
                        .single())
                .isEqualTo(5);
    }

    @Test
    void anEventIsPublishedAgainIfTheRelayDiesBeforeCommittingItsRound() {
        List<StockAdjustedV1> events = List.of(stockAdjusted("DXB-MARINA", 1), stockAdjusted("DXB-MARINA", 2));
        outbox.appendCommitted(events);
        publisher.crashWhen(event -> event.id().equals(events.get(1).eventId()));

        assertThatThrownBy(() -> relay(10).relay()).isInstanceOf(SimulatedCrash.class);
        assertThat(outbox.unpublishedIds()).as("nothing was marked").hasSize(2);

        publisher.heal();
        relay(10).relay();

        assertThat(publisher.published())
                .as("at-least-once: the first event is published twice, nothing is lost")
                .containsExactly(
                        events.get(0).eventId(),
                        events.get(0).eventId(),
                        events.get(1).eventId());
    }

    @Test
    void skipsTheRoundWhileAnotherInstanceHoldsTheRelayLock() throws Exception {
        outbox.appendCommitted(List.of(stockAdjusted("DXB-MARINA", 1)));

        try (Connection otherInstance = outbox.db.dataSource().getConnection()) {
            otherInstance.setAutoCommit(false);
            try (PreparedStatement lock = otherInstance.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, OutboxRepository.RELAY_LOCK_KEY);
                lock.execute();
            }

            assertThat(relay(10).relay()).isEqualTo(RelayRound.SKIPPED);
            assertThat(publisher.published()).isEmpty();

            otherInstance.commit(); // the other leader's round ends and its lock with it
        }
        assertThat(relay(10).relay().published()).isEqualTo(1);
    }

    @Test
    void twoRelaysRunningConcurrentlyNeverPublishTheSameEventTwice() throws Exception {
        List<StockAdjustedV1> events = IntStream.range(0, 120)
                .mapToObj(i -> stockAdjusted(i % 2 == 0 ? "DXB-MARINA" : "DXB-JLT", i))
                .toList();
        outbox.appendCommitted(events);
        List<OutboxRelay> relays = List.of(relay(7), relay(7));
        CyclicBarrier start = new CyclicBarrier(relays.size());

        try (ExecutorService pool = Executors.newFixedThreadPool(relays.size())) {
            List<CompletableFuture<Void>> runs = new ArrayList<>();
            for (OutboxRelay relay : relays) {
                runs.add(CompletableFuture.runAsync(
                        () -> {
                            await(start);
                            while (!outbox.unpublishedIds().isEmpty()) {
                                relay.relay();
                            }
                        },
                        pool));
            }
            CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).join();
        }

        assertThat(publisher.published()).doesNotHaveDuplicates().containsExactlyElementsOf(ids(events));
    }

    @Test
    void countsPublishedAndFailedEventsAndExposesTheBacklog() {
        List<StockAdjustedV1> events =
                List.of(stockAdjusted("DXB-MARINA", 1), stockAdjusted("DXB-MARINA", 2), stockAdjusted("DXB-MARINA", 3));
        outbox.appendCommitted(events);
        new OutboxBacklogMetrics(outbox.repository).bindTo(meters);
        assertThat(meters.get("daqiqa.outbox.backlog").gauge().value()).isEqualTo(3);

        publisher.failWhen(event -> event.id().equals(events.get(2).eventId()));
        relay(10).relay();

        assertThat(meters.get("daqiqa.outbox.published")
                        .tag("type", StockAdjustedV1.TYPE)
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(meters.get("daqiqa.outbox.failures")
                        .tag("type", StockAdjustedV1.TYPE)
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("daqiqa.outbox.backlog").gauge().value()).isEqualTo(1);
    }

    private OutboxRelay relay(int batchSize) {
        return new OutboxRelay(
                outbox.repository,
                publisher,
                outbox.transactionManager,
                OutboxFixtures.CLOCK,
                meters,
                properties(batchSize, 1000));
    }

    /** A relay whose clock reads {@code now}: relays are cheap, so moving time means building another one. */
    private OutboxRelay relayAt(Instant now) {
        return relayAt(now, Duration.ofMinutes(5));
    }

    private OutboxRelay relayAt(Instant now, Duration maxBackoff) {
        return new OutboxRelay(
                outbox.repository,
                publisher,
                outbox.transactionManager,
                Clock.fixed(now, ZoneOffset.UTC),
                meters,
                properties(10, 1000, maxBackoff));
    }

    private Instant nextAttemptAt(UUID id) {
        return outbox.jdbc
                .sql("SELECT next_attempt_at FROM messaging.outbox_event WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    private static List<UUID> ids(List<? extends IntegrationEvent> events) {
        return events.stream().map(IntegrationEvent::eventId).toList();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
