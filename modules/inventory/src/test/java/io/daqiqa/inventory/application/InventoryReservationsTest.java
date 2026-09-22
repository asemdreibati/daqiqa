package io.daqiqa.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.contracts.events.LineItemV1;
import io.daqiqa.contracts.events.ReservationExpiredV1;
import io.daqiqa.inventory.api.InventoryReservations;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.inventory.api.ReservationNoLongerHeldException;
import io.daqiqa.inventory.api.ReservationRequest;
import io.daqiqa.inventory.api.ReservationResult;
import io.daqiqa.inventory.api.ReservationResult.Rejected;
import io.daqiqa.inventory.api.ReservationResult.Reserved;
import io.daqiqa.inventory.api.Shortage;
import io.daqiqa.inventory.support.InventoryIntegrationTest;
import io.daqiqa.inventory.support.InventoryTables.ReservationRow;
import io.daqiqa.inventory.support.InventoryTables.StockRow;
import io.daqiqa.inventory.support.MutableClock;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.SkuQuantity;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class InventoryReservationsTest extends InventoryIntegrationTest {

    private static final Duration HOLD = Duration.ofMinutes(5);

    @Autowired
    InventoryReservations reservations;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void holdsEveryLineOfTheBasketUntilTheHoldRunsOut() {
        tables.stock(MARINA, MILK, 10);
        tables.stock(MARINA, BREAD, 5);

        ReservationResult result = reservations.reserve(basket("order-1", milk(2), bread(1)));

        Reserved reserved = assertReserved(result);
        assertThat(reserved.expiresAt()).isEqualTo(MutableClock.START.plus(HOLD));
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 2, 1));
        assertThat(tables.stockOf(MARINA, BREAD)).isEqualTo(new StockRow(5, 1, 1));
        assertThat(tables.reservation(reserved.reservationId()))
                .isEqualTo(new ReservationRow(
                        reserved.reservationId().value(), "DXB-MARINA", "order-1", "HELD", null, reserved.expiresAt()));
        assertThat(tables.unbalancedBooks()).isEmpty();
    }

    @Test
    void rejectsTheWholeBasketWhenAnyLineIsShortAndWritesNothing() {
        tables.stock(MARINA, MILK, 10);
        tables.stock(MARINA, BREAD, 1);

        ReservationResult result = reservations.reserve(basket("order-1", milk(2), bread(3), water(1)));

        assertThat(result).isEqualTo(new Rejected(List.of(new Shortage(BREAD, 3, 1), new Shortage(WATER, 1, 0))));
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 0, 0));
        assertThat(tables.stockOf(MARINA, BREAD)).isEqualTo(new StockRow(1, 0, 0));
        assertThat(tables.reservations()).isEmpty();
    }

    @Test
    void unitsHeldForAnotherOrderCannotBeReservedAgain() {
        tables.stock(MARINA, MILK, 3);
        reservations.reserve(basket("order-1", milk(2)));

        assertThat(reservations.reserve(basket("order-2", milk(2))))
                .isEqualTo(new Rejected(List.of(new Shortage(MILK, 2, 1))));
    }

    @Test
    void reservingAgainWithTheSameReferenceReturnsTheSameReservationAndHoldsNothingMore() {
        tables.stock(MARINA, MILK, 2);
        Reserved first = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        clock.advance(Duration.ofSeconds(10));

        ReservationResult retry = reservations.reserve(basket("order-1", milk(2)));

        assertThat(retry)
                .as("same id, same expiry, although the stock is gone now")
                .isEqualTo(first);
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(2, 2, 1));
        assertThat(tables.reservations()).hasSize(1);
    }

    @Test
    void aRetryAfterTheHoldRanOutExpiresItAndHoldsTheBasketAfresh() {
        tables.stock(MARINA, MILK, 3);
        Reserved first = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        double replayExpiries = counter("daqiqa.inventory.expirations", "trigger", "replay");
        clock.advance(HOLD); // the hold ran out, and the sweeper has not run

        Reserved retry = assertReserved(reservations.reserve(basket("order-1", milk(2))));

        assertThat(retry.reservationId()).isNotEqualTo(first.reservationId());
        assertThat(retry.expiresAt()).isEqualTo(clock.instant().plus(HOLD));
        assertThat(tables.reservation(first.reservationId()))
                .extracting(ReservationRow::status, ReservationRow::releaseReason)
                .containsExactly("EXPIRED", "EXPIRED");
        assertThat(tables.reservation(retry.reservationId()).status()).isEqualTo("HELD");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isEqualTo(2);
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class))
                .extracting(ReservationExpiredV1::reservationId)
                .containsExactly(first.reservationId().value());
        assertThat(counter("daqiqa.inventory.expirations", "trigger", "replay")).isEqualTo(replayExpiries + 1);
        assertThat(tables.unbalancedBooks()).isEmpty();
    }

    @Test
    void aRetryAfterTheHoldRanOutIsRejectedIfTheStockWentMeanwhileAndTheOldHoldStaysExpired() {
        tables.stock(MARINA, MILK, 3);
        Reserved first = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        clock.advance(HOLD);
        assertReserved(reservations.reserve(basket("order-2", milk(1))));

        ReservationResult retry = reservations.reserve(basket("order-1", milk(3)));

        assertThat(retry).isEqualTo(new Rejected(List.of(new Shortage(MILK, 3, 2))));
        assertThat(tables.reservation(first.reservationId()).status()).isEqualTo("EXPIRED");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isEqualTo(1);
        assertThat(tables.unbalancedBooks()).isEmpty();
    }

    @Test
    void aRetryAtAnotherStoreAfterTheHoldRanOutReturnsTheOldStockAndHoldsTheNewBasket() {
        tables.stock(MARINA, MILK, 3);
        tables.stock(JLT, BREAD, 3);
        Reserved first = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        clock.advance(HOLD.plusSeconds(1));

        Reserved retry =
                assertReserved(reservations.reserve(new ReservationRequest(JLT, "order-1", List.of(bread(2)), HOLD)));

        assertThat(tables.reservation(first.reservationId()).status()).isEqualTo("EXPIRED");
        assertThat(tables.reservation(retry.reservationId()).storeId()).isEqualTo("DXB-JLT");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isZero();
        assertThat(tables.stockOf(JLT, BREAD).reserved()).isEqualTo(2);
    }

    @Test
    void aConfirmedReservationIsReplayedHoweverLongAfterItsHoldTime() {
        tables.stock(MARINA, MILK, 3);
        Reserved first = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        reservations.confirm(first.reservationId());
        clock.advance(HOLD.multipliedBy(3));

        assertThat(reservations.reserve(basket("order-1", milk(2)))).isEqualTo(first);
        assertThat(tables.reservation(first.reservationId()).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void aReferenceWhoseReservationEndedCanReserveAgain() {
        tables.stock(MARINA, MILK, 2);
        Reserved first = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        reservations.release(first.reservationId(), ReleaseReason.PAYMENT_FAILED);

        Reserved second = assertReserved(reservations.reserve(basket("order-1", milk(1))));

        assertThat(second.reservationId()).isNotEqualTo(first.reservationId());
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isEqualTo(1);
    }

    @Test
    void joinsTheCallersTransactionSoItsRollbackUndoesTheReservation() {
        tables.stock(MARINA, MILK, 10);

        inTransaction().executeWithoutResult(status -> {
            assertReserved(reservations.reserve(basket("order-1", milk(2))));
            status.setRollbackOnly(); // the order could not be saved, say
        });

        assertThat(tables.reservations()).isEmpty();
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 0, 0));
    }

    @Test
    void aReservationBecomesVisibleOnlyWhenTheCallersTransactionCommits() {
        tables.stock(MARINA, MILK, 10);

        inTransaction().executeWithoutResult(status -> {
            assertReserved(reservations.reserve(basket("order-1", milk(2))));

            List<ReservationRow> seenFromOutside =
                    CompletableFuture.supplyAsync(tables::reservations).join();
            assertThat(seenFromOutside).isEmpty();
        });

        assertThat(tables.reservations()).hasSize(1);
    }

    @Test
    void confirmingKeepsTheStockHeldAndTheHoldNoLongerExpires() {
        tables.stock(MARINA, MILK, 10);
        Reserved reserved = assertReserved(reservations.reserve(basket("order-1", milk(2))));

        reservations.confirm(reserved.reservationId());
        reservations.confirm(reserved.reservationId()); // idempotent
        clock.advance(HOLD.multipliedBy(2));
        reservations.confirm(reserved.reservationId()); // still a no-op after the original expiry

        assertThat(tables.reservation(reserved.reservationId()).status()).isEqualTo("CONFIRMED");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isEqualTo(2);
    }

    @Test
    void aLateConfirmationExpiresTheHoldReturnsTheStockAndFailsEvenBeforeTheSweeperRuns() {
        tables.stock(MARINA, MILK, 10);
        tables.stock(MARINA, BREAD, 5);
        Reserved reserved = assertReserved(reservations.reserve(basket("order-1", milk(2), bread(1))));
        double lateConfirms = counter("daqiqa.inventory.expirations", "trigger", "late_confirm");
        clock.advance(HOLD);

        assertThatThrownBy(() -> reservations.confirm(reserved.reservationId()))
                .isInstanceOfSatisfying(
                        ReservationNoLongerHeldException.class,
                        e -> assertThat(e.reservationId()).isEqualTo(reserved.reservationId()))
                .hasMessageContaining("EXPIRED");

        assertThat(tables.reservation(reserved.reservationId()))
                .extracting(ReservationRow::status, ReservationRow::releaseReason)
                .containsExactly("EXPIRED", "EXPIRED");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isZero();
        assertThat(tables.stockOf(MARINA, BREAD).reserved()).isZero();
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.reservationId())
                            .isEqualTo(reserved.reservationId().value());
                    assertThat(event.storeId()).isEqualTo("DXB-MARINA");
                    assertThat(event.reference()).isEqualTo("order-1");
                    assertThat(event.lines())
                            .containsExactly(new LineItemV1(BREAD.value(), 1), new LineItemV1(MILK.value(), 2));
                    assertThat(event.occurredAt()).isEqualTo(clock.instant());
                });
        assertThat(counter("daqiqa.inventory.expirations", "trigger", "late_confirm"))
                .isEqualTo(lateConfirms + 1);
    }

    @Test
    void theExpiryOfALateConfirmationIsKeptWhenTheCallerCatchesTheFailureAndCommits() {
        tables.stock(MARINA, MILK, 10);
        Reserved reserved = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        clock.advance(HOLD.plusSeconds(1));

        inTransaction().executeWithoutResult(status -> {
            assertThatThrownBy(() -> reservations.confirm(reserved.reservationId()))
                    .isInstanceOf(ReservationNoLongerHeldException.class);
            // ordering abandons its order here, in the same transaction, and commits
        });

        assertThat(tables.reservation(reserved.reservationId()).status()).isEqualTo("EXPIRED");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isZero();
        assertThat(outbox.committedEventsOfType(ReservationExpiredV1.class)).hasSize(1);
    }

    @Test
    void anEndedReservationCannotBeConfirmed() {
        tables.stock(MARINA, MILK, 10);
        Reserved reserved = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        reservations.release(reserved.reservationId(), ReleaseReason.ORDER_CANCELLED);

        assertThatThrownBy(() -> reservations.confirm(reserved.reservationId()))
                .isInstanceOf(ReservationNoLongerHeldException.class)
                .hasMessageContaining("RELEASED");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isZero();
    }

    @Test
    void releasingPutsTheStockBackOnSaleAndRecordsWhy() {
        tables.stock(MARINA, MILK, 10);
        tables.stock(MARINA, BREAD, 5);
        Reserved reserved = assertReserved(reservations.reserve(basket("order-1", milk(2), bread(5))));
        double paymentFailures = counter("daqiqa.inventory.releases", "reason", "payment_failed");

        reservations.release(reserved.reservationId(), ReleaseReason.PAYMENT_FAILED);

        assertThat(tables.reservation(reserved.reservationId()))
                .extracting(ReservationRow::status, ReservationRow::releaseReason)
                .containsExactly("RELEASED", "PAYMENT_FAILED");
        assertThat(tables.stockOf(MARINA, MILK)).isEqualTo(new StockRow(10, 0, 2));
        assertThat(tables.stockOf(MARINA, BREAD)).isEqualTo(new StockRow(5, 0, 2));
        assertThat(counter("daqiqa.inventory.releases", "reason", "payment_failed"))
                .isEqualTo(paymentFailures + 1);
        assertThat(outbox.committedEvents())
                .as("a release is ordering's decision, not news")
                .isEmpty();
    }

    @Test
    void releasingAConfirmedReservationPutsItsStockBackToo() {
        tables.stock(MARINA, MILK, 10);
        Reserved reserved = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        reservations.confirm(reserved.reservationId());

        reservations.release(reserved.reservationId(), ReleaseReason.ORDER_CANCELLED);

        assertThat(tables.reservation(reserved.reservationId()).status()).isEqualTo("RELEASED");
        assertThat(tables.stockOf(MARINA, MILK).reserved()).isZero();
    }

    @Test
    void releasingAgainOrAfterExpiryReturnsNothingTwice() {
        tables.stock(MARINA, MILK, 10);
        Reserved released = assertReserved(reservations.reserve(basket("order-1", milk(2))));
        Reserved expired = assertReserved(reservations.reserve(basket("order-2", milk(3))));
        Reserved stillHeld = assertReserved(reservations.reserve(basket("order-3", milk(4))));
        reservations.release(released.reservationId(), ReleaseReason.ORDER_CANCELLED);
        clock.advance(HOLD);
        assertThatThrownBy(() -> reservations.confirm(expired.reservationId()))
                .isInstanceOf(ReservationNoLongerHeldException.class);

        reservations.release(released.reservationId(), ReleaseReason.OPERATOR);
        reservations.release(expired.reservationId(), ReleaseReason.OPERATOR);

        assertThat(tables.stockOf(MARINA, MILK).reserved()).isEqualTo(4);
        assertThat(tables.reservation(released.reservationId()).releaseReason()).isEqualTo("ORDER_CANCELLED");
        assertThat(tables.reservation(stillHeld.reservationId()).status()).isEqualTo("HELD");
        assertThat(tables.unbalancedBooks()).isEmpty();
    }

    @Test
    void confirmingOrReleasingAReservationThatNeverExistedIsAnError() {
        ReservationId unknown = ReservationId.newId();

        assertThatIllegalArgumentException().isThrownBy(() -> reservations.confirm(unknown));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> reservations.release(unknown, ReleaseReason.ORDER_CANCELLED));
    }

    @Test
    void countsReservationsByOutcomeAndTimesEveryAttempt() {
        tables.stock(MARINA, MILK, 1);
        double reserved = counter("daqiqa.inventory.reservations", "outcome", "reserved");
        double rejected = counter("daqiqa.inventory.reservations", "outcome", "rejected");
        double replayed = counter("daqiqa.inventory.reservations", "outcome", "replayed");
        long timed = meters.get("daqiqa.inventory.reserve").timer().count();

        reservations.reserve(basket("order-1", milk(1)));
        reservations.reserve(basket("order-1", milk(1)));
        reservations.reserve(basket("order-2", milk(1)));

        assertThat(counter("daqiqa.inventory.reservations", "outcome", "reserved"))
                .isEqualTo(reserved + 1);
        assertThat(counter("daqiqa.inventory.reservations", "outcome", "replayed"))
                .isEqualTo(replayed + 1);
        assertThat(counter("daqiqa.inventory.reservations", "outcome", "rejected"))
                .isEqualTo(rejected + 1);
        assertThat(meters.get("daqiqa.inventory.reserve").timer().count()).isEqualTo(timed + 3);
    }

    @Test
    void countsChangesOnlyOnceTheyCommitButEveryAnswerAtOnce() {
        tables.stock(MARINA, MILK, 3);
        Reserved runningLate = assertReserved(reservations.reserve(basket("order-0", milk(1))));
        clock.advance(HOLD);
        double reserved = counter("daqiqa.inventory.reservations", "outcome", "reserved");
        double rejected = counter("daqiqa.inventory.reservations", "outcome", "rejected");
        double lateConfirms = counter("daqiqa.inventory.expirations", "trigger", "late_confirm");

        inTransaction().executeWithoutResult(status -> {
            assertReserved(reservations.reserve(basket("order-1", milk(1))));
            assertThat(reservations.reserve(basket("order-2", milk(5)))).isInstanceOf(Rejected.class);
            assertThatThrownBy(() -> reservations.confirm(runningLate.reservationId()))
                    .isInstanceOf(ReservationNoLongerHeldException.class);
            status.setRollbackOnly(); // the caller's order failed: none of it happened
        });

        assertThat(counter("daqiqa.inventory.reservations", "outcome", "reserved"))
                .isEqualTo(reserved);
        assertThat(counter("daqiqa.inventory.expirations", "trigger", "late_confirm"))
                .isEqualTo(lateConfirms);
        assertThat(counter("daqiqa.inventory.reservations", "outcome", "rejected"))
                .as("the caller was told, whatever became of its transaction")
                .isEqualTo(rejected + 1);
    }

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static Reserved assertReserved(ReservationResult result) {
        assertThat(result).isInstanceOf(Reserved.class);
        return (Reserved) result;
    }

    private static ReservationRequest basket(String reference, SkuQuantity... lines) {
        return new ReservationRequest(MARINA, reference, List.of(lines), HOLD);
    }

    private static SkuQuantity milk(int quantity) {
        return new SkuQuantity(MILK, quantity);
    }

    private static SkuQuantity bread(int quantity) {
        return new SkuQuantity(BREAD, quantity);
    }

    private static SkuQuantity water(int quantity) {
        return new SkuQuantity(WATER, quantity);
    }
}
