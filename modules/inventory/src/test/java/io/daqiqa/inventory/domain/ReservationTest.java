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
import org.jspecify.annotations.Nullable;
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
        assertThat(held.endReason()).isNull();
        assertThat(held.expiresAt()).isEqualTo(NOW.plus(HOLD));
        assertThat(held.isHeldPast(NOW.plus(HOLD).minusNanos(1))).isFalse();
        assertThat(held.isHeldPast(NOW.plus(HOLD))).isTrue();
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
    void hasAnEndReasonExactlyWhenItHasEnded() {
        assertThatIllegalArgumentException().isThrownBy(() -> with(held, ReservationStatus.HELD, EndReason.OPERATOR));
        assertThatIllegalArgumentException().isThrownBy(() -> with(held, ReservationStatus.RELEASED, null));
    }

    @Test
    void confirmingInTimeConfirms() {
        assertThat(held.confirm(NOW.plus(HOLD).minusMillis(1)))
                .isEqualTo(new Confirmed(new Transition(held, with(held, ReservationStatus.CONFIRMED, null))));
    }

    @Test
    void aConfirmationAtOrAfterTheExpiryTimeExpiresTheHoldInsteadEvenBeforeTheSweeperRuns() {
        Transition expiry = new Transition(held, with(held, ReservationStatus.EXPIRED, EndReason.EXPIRED));

        assertThat(held.confirm(NOW.plus(HOLD))).isEqualTo(new HoldElapsed(expiry));
        assertThat(held.confirm(NOW.plus(HOLD).plusSeconds(1))).isEqualTo(new HoldElapsed(expiry));
    }

    @Test
    void confirmingTwiceIsANoOpEvenAfterTheOriginalExpiryTime() {
        Reservation confirmed = with(held, ReservationStatus.CONFIRMED, null);

        assertThat(confirmed.confirm(NOW.plus(Duration.ofDays(1)))).isEqualTo(new AlreadyConfirmed());
        assertThat(confirmed.isHeldPast(NOW.plus(Duration.ofDays(1))))
                .as("a confirmed hold never runs out")
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "EXPIRED"})
    void anEndedReservationCannotBeConfirmed(ReservationStatus ended) {
        assertThat(with(held, ended, EndReason.OPERATOR).confirm(NOW)).isEqualTo(new NoLongerHeld(ended));
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"HELD", "CONFIRMED"})
    void releasingPutsHeldOrConfirmedStockBackOnSaleAndRecordsWhy(ReservationStatus holding) {
        Reservation holdingStock = with(held, holding, null);

        assertThat(holdingStock.release(EndReason.PAYMENT_FAILED))
                .isEqualTo(new Released(new Transition(
                        holdingStock, with(held, ReservationStatus.RELEASED, EndReason.PAYMENT_FAILED))));
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"RELEASED", "EXPIRED"})
    void releasingAnEndedReservationIsANoOp(ReservationStatus ended) {
        assertThat(with(held, ended, EndReason.ORDER_CANCELLED).release(EndReason.OPERATOR))
                .isEqualTo(new AlreadyEnded(ended));
    }

    @Test
    void theSweeperExpiresOnlyHeldReservationsWhoseTimeRanOut() {
        assertThat(held.expire(NOW.plus(HOLD)))
                .isEqualTo(new Transition(held, with(held, ReservationStatus.EXPIRED, EndReason.EXPIRED)));
        assertThatIllegalStateException()
                .isThrownBy(() -> held.expire(NOW.plus(HOLD).minusMillis(1)));
        assertThatIllegalStateException()
                .isThrownBy(() -> with(held, ReservationStatus.CONFIRMED, null).expire(NOW.plus(HOLD)));
    }

    @Test
    void aTransitionMovesOneReservationToAnotherStatus() {
        Reservation other = Reservation.hold(ReservationId.newId(), MARINA, "order-2", held.lines(), NOW, HOLD);

        assertThatIllegalArgumentException().isThrownBy(() -> new Transition(held, held));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Transition(held, with(other, ReservationStatus.CONFIRMED, null)));
    }

    private static Reservation with(Reservation reservation, ReservationStatus status, @Nullable EndReason reason) {
        return new Reservation(
                reservation.id(),
                reservation.storeId(),
                reservation.reference(),
                reservation.lines(),
                status,
                reservation.expiresAt(),
                reason);
    }
}
