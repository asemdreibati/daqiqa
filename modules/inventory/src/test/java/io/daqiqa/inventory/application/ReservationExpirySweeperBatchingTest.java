package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.daqiqa.kernel.ReservationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

/** The sweeper's loop on its own: batches, the one-by-one fallback, and why a sweep always ends. */
class ReservationExpirySweeperBatchingTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00.123456789Z");
    private static final Instant STORABLE_NOW = Instant.parse("2026-09-22T10:00:00.123456Z");

    private static final Duration LOCK_TIMEOUT = Duration.ofMillis(500);

    private final ReservationService reservations = mock(ReservationService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void takesBatchesUntilOneIsNotFullAllAsOfTheSameMomentAtTheStoredPrecision() {
        given(reservations.expireElapsedHolds(STORABLE_NOW, 3, LOCK_TIMEOUT)).willReturn(3, 3, 1);

        assertThat(sweeper(3).sweep()).isEqualTo(7);
        verify(reservations, times(3)).expireElapsedHolds(STORABLE_NOW, 3, LOCK_TIMEOUT);
    }

    @Test
    void whenABatchFailsExpiresItsHoldsOneByOneSoOnePoisonHoldCannotStopTheOthers() {
        ReservationId poison = ReservationId.newId();
        ReservationId healthy = ReservationId.newId();
        given(reservations.expireElapsedHolds(STORABLE_NOW, 2, LOCK_TIMEOUT))
                .willThrow(new IllegalStateException("reserved stock is inconsistent"))
                .willReturn(0);
        given(reservations.elapsedHoldIds(STORABLE_NOW, 2)).willReturn(List.of(poison, healthy));
        given(reservations.expireElapsedHold(poison, STORABLE_NOW, LOCK_TIMEOUT))
                .willThrow(new IllegalStateException("reserved stock is inconsistent"));
        given(reservations.expireElapsedHold(healthy, STORABLE_NOW, LOCK_TIMEOUT))
                .willReturn(true);

        assertThat(sweeper(2).sweep()).isEqualTo(1);
        assertThat(failures("batch")).isEqualTo(1);
        assertThat(failures("hold")).isEqualTo(1);
    }

    @Test
    void aFullBatchOfHoldsThatAllFailEndsTheSweepInsteadOfLoopingForever() {
        ReservationId first = ReservationId.newId();
        ReservationId second = ReservationId.newId();
        given(reservations.expireElapsedHolds(STORABLE_NOW, 2, LOCK_TIMEOUT))
                .willThrow(new IllegalStateException("broken"));
        given(reservations.elapsedHoldIds(STORABLE_NOW, 2)).willReturn(List.of(first, second));
        given(reservations.expireElapsedHold(first, STORABLE_NOW, LOCK_TIMEOUT))
                .willThrow(new IllegalStateException("broken"));
        given(reservations.expireElapsedHold(second, STORABLE_NOW, LOCK_TIMEOUT))
                .willThrow(new IllegalStateException("broken"));

        assertThat(sweeper(2).sweep()).isZero();
        verify(reservations, times(1)).expireElapsedHolds(STORABLE_NOW, 2, LOCK_TIMEOUT);
        assertThat(failures("hold")).isEqualTo(2);
    }

    @Test
    void contentionLeavesTheBatchToTheNextRoundInsteadOfBlamingItsHolds() {
        given(reservations.expireElapsedHolds(STORABLE_NOW, 2, LOCK_TIMEOUT))
                .willThrow(new UncategorizedSQLException("expire", "UPDATE inventory.stock_level ...", lockTimedOut()));

        assertThat(sweeper(2).sweep()).isZero();

        assertThat(contentions()).isEqualTo(1);
        assertThat(failures("batch")).isZero();
        assertThat(failures("hold")).isZero();
        verify(reservations, never()).elapsedHoldIds(any(), anyInt());
    }

    @Test
    void contentionOnOneHoldOfAFailedBatchIsNotBlamedOnThatHoldEither() {
        ReservationId contended = ReservationId.newId();
        given(reservations.expireElapsedHolds(STORABLE_NOW, 1, LOCK_TIMEOUT))
                .willThrow(new IllegalStateException("reserved stock is inconsistent"));
        given(reservations.elapsedHoldIds(STORABLE_NOW, 1)).willReturn(List.of(contended));
        given(reservations.expireElapsedHold(contended, STORABLE_NOW, LOCK_TIMEOUT))
                .willThrow(new UncategorizedSQLException("expire", "UPDATE ...", lockTimedOut()));

        assertThat(sweeper(1).sweep()).isZero();

        assertThat(contentions()).isEqualTo(1);
        assertThat(failures("hold")).isZero();
        assertThat(failures("batch")).isEqualTo(1);
    }

    @Test
    void needsAPositiveBatchSizeAndLockTimeout() {
        assertThatIllegalArgumentException().isThrownBy(() -> sweeper(0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReservationExpirySweeper(
                        reservations, new InventoryMetrics(registry), clock, 1, Duration.ZERO));
    }

    private static SQLException lockTimedOut() {
        return new SQLException("canceling statement due to lock timeout", "55P03");
    }

    private ReservationExpirySweeper sweeper(int batchSize) {
        return new ReservationExpirySweeper(
                reservations, new InventoryMetrics(registry), clock, batchSize, LOCK_TIMEOUT);
    }

    private double contentions() {
        return registry.get(InventoryMetrics.EXPIRY_CONTENTION).counter().count();
    }

    private double failures(String scope) {
        return registry.get(InventoryMetrics.EXPIRY_FAILURES)
                .tag("scope", scope)
                .counter()
                .count();
    }
}
