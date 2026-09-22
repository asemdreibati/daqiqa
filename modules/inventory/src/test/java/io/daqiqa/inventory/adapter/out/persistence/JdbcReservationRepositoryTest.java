package io.daqiqa.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.daqiqa.inventory.domain.ConfirmOutcome;
import io.daqiqa.inventory.domain.ReleaseOutcome;
import io.daqiqa.inventory.domain.Reservation;
import io.daqiqa.inventory.domain.ReservationStatus;
import io.daqiqa.inventory.support.InventoryTables.ReservationRow;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JdbcReservationRepositoryTest extends PostgresRepositoryTest {

    private static final StoreId MARINA = StoreId.of("DXB-MARINA");
    private static final Sku MILK = Sku.of("MILK-FULL-1L");
    private static final Sku BREAD = Sku.of("BREAD-ARABIC-6PK");
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(5);

    private final JdbcReservationRepository repository = new JdbcReservationRepository(jdbc);

    @Test
    void storesAHoldWithItsLinesAndFindsItByReference() {
        Reservation hold = hold("order-1", new SkuQuantity(MILK, 2), new SkuQuantity(BREAD, 1));

        assertThat(repository.insertIfReferenceFree(hold, NOW)).isTrue();

        assertThat(repository.findActiveByReference("order-1")).contains(hold);
        assertThat(tables.linesOf(hold.id())).isEqualTo(Map.of(MILK.value(), 2, BREAD.value(), 1));
        assertThat(repository.findActiveByReference("order-2")).isEmpty();
    }

    @Test
    void aReferenceWithAnActiveReservationIsRefusedWithoutAnErrorThatWouldAbortTheTransaction() {
        Reservation first = hold("order-1", new SkuQuantity(MILK, 2));
        Reservation second = hold("order-1", new SkuQuantity(BREAD, 1));

        transaction.executeWithoutResult(status -> {
            assertThat(repository.insertIfReferenceFree(first, NOW)).isTrue();
            assertThat(repository.insertIfReferenceFree(second, NOW)).isFalse();
            assertThat(repository.findActiveByReference("order-1"))
                    .as("the transaction is still usable")
                    .contains(first);
        });

        assertThat(tables.reservations())
                .extracting(ReservationRow::id)
                .containsExactly(first.id().value());
    }

    @Test
    void aReferenceCanReserveAgainOnceItsReservationEnded() {
        Reservation first = hold("order-1", new SkuQuantity(MILK, 2));
        repository.insertIfReferenceFree(first, NOW);
        Reservation released = ((ReleaseOutcome.Released) first.release()).released();
        repository.updateStatus(List.of(released), "ORDER_CANCELLED", NOW);

        Reservation again = hold("order-1", new SkuQuantity(MILK, 1));

        assertThat(repository.insertIfReferenceFree(again, NOW)).isTrue();
        assertThat(repository.findActiveByReference("order-1")).contains(again);
    }

    @Test
    void aSecondInsertOfTheSameReferenceWaitsForTheFirstToCommitAndThenGivesWay() throws Exception {
        Reservation first = hold("order-1", new SkuQuantity(MILK, 2));
        Reservation second = hold("order-1", new SkuQuantity(MILK, 2));

        Future<Boolean> secondInsert = transaction.execute(status -> {
            repository.insertIfReferenceFree(first, NOW);
            Future<Boolean> waiting = startInAnotherTransaction(() -> repository.insertIfReferenceFree(second, NOW));
            try {
                awaitAnotherSessionWaitingForALock();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return waiting;
        });

        assertThat(secondInsert.get(30, TimeUnit.SECONDS)).isFalse();
        assertThat(tables.reservations())
                .extracting(ReservationRow::id)
                .containsExactly(first.id().value());
    }

    @Test
    void aSecondInsertOfTheSameReferenceGoesAheadIfTheFirstRollsBack() throws Exception {
        Reservation first = hold("order-1", new SkuQuantity(MILK, 2));
        Reservation second = hold("order-1", new SkuQuantity(MILK, 2));

        Future<Boolean> secondInsert = transaction.execute(status -> {
            repository.insertIfReferenceFree(first, NOW);
            Future<Boolean> waiting = startInAnotherTransaction(() -> repository.insertIfReferenceFree(second, NOW));
            try {
                awaitAnotherSessionWaitingForALock();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            status.setRollbackOnly();
            return waiting;
        });

        assertThat(secondInsert.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(tables.reservations())
                .extracting(ReservationRow::id)
                .containsExactly(second.id().value());
    }

    @Test
    void locksAReservationByIdWithItsLinesInSkuOrder() {
        Reservation hold = hold("order-1", new SkuQuantity(MILK, 2), new SkuQuantity(BREAD, 1));
        repository.insertIfReferenceFree(hold, NOW);

        transaction.executeWithoutResult(status -> {
            assertThat(repository.lockById(hold.id())).contains(hold);
            assertThat(hold.lines()).extracting(SkuQuantity::sku).containsExactly(BREAD, MILK);

            assertLockNotAvailable(() -> inAnotherTransaction(() -> {
                waitForLocksAtMost(Duration.ofMillis(200));
                return repository.lockById(hold.id());
            }));
        });
        assertThat(repository.lockById(ReservationId.newId())).isEmpty();
    }

    @Test
    void findsHeldReservationsWhoseTimeRanOutEarliestFirstUpToTheLimit() {
        Reservation expiresFirst = expiringAt("order-1", NOW.minusSeconds(60));
        Reservation expiresSecond = expiringAt("order-2", NOW.minusSeconds(30));
        Reservation expiresExactlyNow = expiringAt("order-3", NOW);
        Reservation stillRunning = expiringAt("order-4", NOW.plusMillis(1));
        Reservation confirmed = expiringAt("order-5", NOW.minusSeconds(90));
        List.of(expiresSecond, expiresExactlyNow, expiresFirst, stillRunning, confirmed)
                .forEach(reservation -> repository.insertIfReferenceFree(reservation, NOW));
        Reservation confirmation = ((ConfirmOutcome.Confirmed) confirmed.confirm(NOW.minus(HOLD))).confirmed();
        repository.updateStatus(List.of(confirmation), null, NOW);

        List<Reservation> elapsed = transaction.execute(status -> repository.lockElapsedHolds(NOW, 10));
        List<Reservation> firstTwo = transaction.execute(status -> repository.lockElapsedHolds(NOW, 2));

        assertThat(elapsed).containsExactly(expiresFirst, expiresSecond, expiresExactlyNow);
        assertThat(firstTwo).containsExactly(expiresFirst, expiresSecond);
    }

    @Test
    void skipsElapsedHoldsThatAnotherTransactionHasLocked() {
        Reservation lockedElsewhere = expiringAt("order-1", NOW.minusSeconds(60));
        Reservation free = expiringAt("order-2", NOW.minusSeconds(30));
        repository.insertIfReferenceFree(lockedElsewhere, NOW);
        repository.insertIfReferenceFree(free, NOW);

        transaction.executeWithoutResult(status -> {
            assertThat(repository.lockElapsedHolds(NOW, 1)).containsExactly(lockedElsewhere);

            assertThat(inAnotherTransaction(() -> {
                        waitForLocksAtMost(Duration.ofMillis(200));
                        return repository.lockElapsedHolds(NOW, 10);
                    }))
                    .as("a concurrent sweep neither waits nor takes the same hold")
                    .containsExactly(free);
        });
    }

    @Test
    void recordsNewStatusesWithTheReleaseReason() {
        Reservation first = hold("order-1", new SkuQuantity(MILK, 1));
        Reservation second = hold("order-2", new SkuQuantity(MILK, 1));
        repository.insertIfReferenceFree(first, NOW);
        repository.insertIfReferenceFree(second, NOW);

        repository.updateStatus(
                List.of(first.expire(first.expiresAt()), second.expire(second.expiresAt())), "EXPIRED", NOW);

        assertThat(tables.reservations())
                .extracting(ReservationRow::status, ReservationRow::releaseReason)
                .containsOnly(tuple("EXPIRED", "EXPIRED"));
        assertThat(repository.findActiveByReference("order-1")).isEmpty();
    }

    @Test
    void refusesToRecordTheStatusOfAReservationThatDoesNotExist() {
        Reservation unknown = hold("order-1", new SkuQuantity(MILK, 1));

        assertThatIllegalStateException()
                .isThrownBy(() -> repository.updateStatus(
                        List.of(((ReleaseOutcome.Released) unknown.release()).released()), "OPERATOR", NOW));
    }

    @Test
    void theDatabaseRefusesAnEndedReservationWithoutAReason() {
        Reservation hold = hold("order-1", new SkuQuantity(MILK, 1));
        repository.insertIfReferenceFree(hold, NOW);

        assertThatThrownBy(() -> repository.updateStatus(
                        List.of(((ReleaseOutcome.Released) hold.release()).released()), null, NOW))
                .hasMessageContaining("reservation_reason_iff_ended");
        assertThat(repository.findActiveByReference("order-1"))
                .map(Reservation::status)
                .contains(ReservationStatus.HELD);
    }

    private static Reservation hold(String reference, SkuQuantity... lines) {
        return Reservation.hold(ReservationId.newId(), MARINA, reference, List.of(lines), NOW, HOLD);
    }

    private static Reservation expiringAt(String reference, Instant expiresAt) {
        return Reservation.hold(
                ReservationId.newId(),
                MARINA,
                reference,
                List.of(new SkuQuantity(MILK, 1)),
                expiresAt.minus(HOLD),
                HOLD);
    }
}
