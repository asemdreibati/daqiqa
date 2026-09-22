package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.daqiqa.inventory.api.ReservationRequest;
import io.daqiqa.inventory.api.ReservationResult.Reserved;
import io.daqiqa.inventory.domain.Reservation;
import io.daqiqa.inventory.domain.ReservationRepository;
import io.daqiqa.inventory.domain.StockLevel;
import io.daqiqa.inventory.domain.StockLevelRepository;
import io.daqiqa.inventory.domain.StoreStock;
import io.daqiqa.inventory.domain.Transition;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import io.daqiqa.messaging.RecordingOutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The branches of {@link ReservationService#reserve} that only a race reaches, played deterministically: the
 * concurrency tests show they happen, these show exactly what each one does.
 */
class ReservationServiceTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(5);
    private static final ReservationRequest REQUEST =
            new ReservationRequest(MARINA, "order-1", List.of(new SkuQuantity(MILK, 2)), HOLD);

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final StockLevelRepository stockLevels = mock(StockLevelRepository.class);
    private final ReservationService service = new ReservationService(
            reservations,
            stockLevels,
            new RecordingOutboxWriter(),
            new InventoryMetrics(new SimpleMeterRegistry()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final Reservation winner = Reservation.hold(
            ReservationId.newId(), MARINA, "order-1", REQUEST.lines(), NOW.minusSeconds(1), Duration.ofMinutes(5));

    @Test
    void aShortfallThatIsReallyAConcurrentFirstCallsHoldReturnsThatCallsReservation() {
        // Both calls missed each other before the lock; the winner committed while this one waited for it.
        given(reservations.findActiveByReference("order-1"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(winner));
        given(stockLevels.lockForUpdate(MARINA, List.of(MILK)))
                .willReturn(StoreStock.of(MARINA, List.of(new StockLevel(MARINA, MILK, 2, 2))));

        assertThat(service.reserve(REQUEST)).isEqualTo(new Reserved(winner.id(), winner.expiresAt()));
        verify(reservations, never()).insertIfReferenceFree(any(), any());
        verify(stockLevels, never()).reserve(any(), any(), any());
    }

    @Test
    void aReferenceTakenWhileThisCallWaitedToInsertReturnsTheWinnerAndHoldsNothing() {
        given(reservations.findActiveByReference("order-1"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(winner));
        given(stockLevels.lockForUpdate(MARINA, List.of(MILK)))
                .willReturn(StoreStock.of(MARINA, List.of(new StockLevel(MARINA, MILK, 10, 2))));
        given(reservations.insertIfReferenceFree(any(), any())).willReturn(false);

        assertThat(service.reserve(REQUEST)).isEqualTo(new Reserved(winner.id(), winner.expiresAt()));
        verify(stockLevels, never()).reserve(any(), any(), any());
    }

    @Test
    void aRunOutHoldThatWasSweptWhileThisCallWaitedForItsLockIsLeftAloneAndTheBasketHeldAfresh() {
        Reservation runOut = Reservation.hold(
                ReservationId.newId(), MARINA, "order-1", REQUEST.lines(), NOW.minus(Duration.ofMinutes(10)), HOLD);
        Transition sweptMeanwhile = runOut.expire(NOW);
        given(reservations.findActiveByReference("order-1")).willReturn(Optional.of(runOut));
        given(reservations.lockById(runOut.id())).willReturn(Optional.of(sweptMeanwhile.to()));
        given(stockLevels.lockForUpdate(MARINA, List.of(MILK)))
                .willReturn(StoreStock.of(MARINA, List.of(new StockLevel(MARINA, MILK, 10, 0))));
        given(reservations.insertIfReferenceFree(any(), any())).willReturn(true);

        assertThat(service.reserve(REQUEST))
                .isInstanceOfSatisfying(
                        Reserved.class,
                        reserved -> assertThat(reserved.reservationId()).isNotEqualTo(runOut.id()));
        verify(reservations, never()).apply(any(), any());
        verify(stockLevels, never()).release(any(), any());
        verify(stockLevels).reserve(MARINA, REQUEST.lines(), NOW);
    }

    @Test
    void aWinnerThatEndedBeforeItCouldBeReturnedFailsTheCallSoTheCallerRetries() {
        given(reservations.findActiveByReference("order-1")).willReturn(Optional.empty());
        given(stockLevels.lockForUpdate(MARINA, List.of(MILK)))
                .willReturn(StoreStock.of(MARINA, List.of(new StockLevel(MARINA, MILK, 10, 0))));
        given(reservations.insertIfReferenceFree(any(), any())).willReturn(false);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.reserve(REQUEST))
                .withMessageContaining("retry");
        verify(stockLevels, never()).reserve(any(), any(), any());
    }
}
