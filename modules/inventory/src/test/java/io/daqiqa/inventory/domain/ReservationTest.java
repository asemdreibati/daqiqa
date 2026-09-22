package io.daqiqa.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.daqiqa.inventory.domain.ConfirmOutcome.AlreadyConfirmed;
import io.daqiqa.inventory.domain.ConfirmOutcome.Confirmed;
import io.daqiqa.inventory.domain.ConfirmOutcome.HoldElapsed;
import io.daqiqa.inventory.domain.ConfirmOutcome.NoLongerHeld;
import io.daqiqa.inventory.domain.ReleaseOutcome.AlreadyEnded;
import io.daqiqa.inventory.domain.ReleaseOutcome.Released;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(5);

    private final Reservation held = Reservation.hold(
            ReservationId.newId(),
            MARINA,
            "order-1",
            List.of(new SkuQuantity(MILK, 2), new SkuQuantity(BREAD, 1)),
            NOW,
            HOLD);

    @Test
    void aNewHoldIsHeldUntilItsHoldTimeRunsOut() {
        assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(held.expiresAt()).isEqualTo(NOW.plus(HOLD));
        assertThat(held.isElapsedAt(NOW.plus(HOLD).minusNanos(1))).isFalse();
        assertThat(held.isElapsedAt(NOW.plus(HOLD))).isTrue();
    }

    @Test
    void keepsItsLinesInSkuOrderTheOrderStockRowsAreLockedIn() {
        assertThat(held.lines()).extracting(SkuQuantity::sku).containsExactly(BREAD, MILK);
    }

    @Test
    void holdsEachSkuOnce() {
        List<SkuQuantity> twice = List.of(new SkuQuantity(MILK, 1), new SkuQuantity(MILK, 2));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Reservation.hold(ReservationId.newId(), MARINA, "order-1", twice, NOW, HOLD));
    }

    @Test
    void needsLinesAReferenceAndAPositiveHold() {
        List<SkuQuantity> lines = List.of(new SkuQuantity(MILK, 1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Reservation.hold(ReservationId.newId(), MARINA, "order-1", List.of(), NOW, HOLD));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Reservation.hold(ReservationId.newId(), MARINA, " ", lines, NOW, HOLD));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Reservation.hold(ReservationId.newId(), MARINA, "x".repeat(65), lines, NOW, HOLD));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> Reservation.hold(ReservationId.newId(), MARINA, "order-1", lines, NOW, Duration.ZERO));
    }

    @Test
    void confirmingInTimeConfirms() {
        assertThat(held.confirm(NOW.plus(HOLD).minusMillis(1)))
                .isInstanceOfSatisfying(
                        Confirmed.class,
                        confirmed -> assertThat(confirmed.confirmed())
                                .isEqualTo(withStatus(held, ReservationStatus.CONFIRMED)));
    }

    @Test
    void aConfirmationAtOrAfterTheExpiryTimeExpiresTheHoldInsteadEvenBeforeTheSweeperRuns() {
        for (Instant late : List.of(NOW.plus(HOLD), NOW.plus(HOLD).plusSeconds(1))) {
            assertThat(held.confirm(late))
                    .isInstanceOfSatisfying(
                            HoldElapsed.class,
                            elapsed -> assertThat(elapsed.expired())
                                    .isEqualTo(withStatus(held, ReservationStatus.EXPIRED)));
        }
    }

    @Test
    void confirmingTwiceIsANoOpEvenAfterTheOriginalExpiryTime() {
        Reservation confirmed = withStatus(held, ReservationStatus.CONFIRMED);

        assertThat(confirmed.confirm(NOW.plus(Duration.ofDays(1)))).isEqualTo(new AlreadyConfirmed());
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "EXPIRED"})
    void anEndedReservationCannotBeConfirmed(ReservationStatus ended) {
        assertThat(withStatus(held, ended).confirm(NOW)).isEqualTo(new NoLongerHeld(ended));
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"HELD", "CONFIRMED"})
    void releasingPutsHeldOrConfirmedStockBackOnSale(ReservationStatus holding) {
        assertThat(withStatus(held, holding).release())
                .isEqualTo(new Released(withStatus(held, ReservationStatus.RELEASED)));
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "EXPIRED"})
    void releasingAnEndedReservationIsANoOp(ReservationStatus ended) {
        assertThat(withStatus(held, ended).release()).isEqualTo(new AlreadyEnded(ended));
    }

    @Test
    void theSweeperExpiresOnlyHeldReservationsWhoseTimeRanOut() {
        assertThat(held.expire(NOW.plus(HOLD))).isEqualTo(withStatus(held, ReservationStatus.EXPIRED));
        assertThatIllegalStateException()
                .isThrownBy(() -> held.expire(NOW.plus(HOLD).minusMillis(1)));
        assertThatIllegalStateException()
                .isThrownBy(() -> withStatus(held, ReservationStatus.CONFIRMED).expire(NOW.plus(HOLD)));
    }

    @Test
    void onlyHeldAndConfirmedReservationsCountAsReservedStock() {
        assertThat(ReservationStatus.values())
                .filteredOn(ReservationStatus::holdsStock)
                .containsExactly(ReservationStatus.HELD, ReservationStatus.CONFIRMED);
    }

    private static Reservation withStatus(Reservation reservation, ReservationStatus status) {
        return new Reservation(
                reservation.id(),
                reservation.storeId(),
                reservation.reference(),
                reservation.lines(),
                status,
                reservation.expiresAt());
    }
}
