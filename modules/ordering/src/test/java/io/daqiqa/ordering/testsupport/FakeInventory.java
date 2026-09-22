package io.daqiqa.ordering.testsupport;

import io.daqiqa.inventory.api.InventoryReservations;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.inventory.api.ReservationNoLongerHeldException;
import io.daqiqa.inventory.api.ReservationRequest;
import io.daqiqa.inventory.api.ReservationResult;
import io.daqiqa.inventory.api.Shortage;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * In-memory inventory honouring the {@link InventoryReservations} contract: all-or-nothing holds that never oversell,
 * idempotent by reference; a confirm after the hold expired expires it and refuses, without touching the caller's
 * transaction (as the real one documents); release is idempotent. Like the real one it joins the caller's
 * transaction: what it changes inside a Spring transaction is undone if that transaction rolls back. Thread-safe.
 */
public final class FakeInventory implements InventoryReservations {

    public enum Status {
        HELD,
        CONFIRMED,
        RELEASED,
        EXPIRED
    }

    public record Reservation(
            ReservationId id,
            StoreId storeId,
            String reference,
            List<SkuQuantity> lines,
            Instant expiresAt,
            Status status,
            @Nullable ReleaseReason releaseReason) {

        boolean active() {
            return status == Status.HELD || status == Status.CONFIRMED;
        }

        Reservation becoming(Status next, @Nullable ReleaseReason reason) {
            return new Reservation(id, storeId, reference, lines, expiresAt, next, reason);
        }
    }

    private record Key(StoreId store, Sku sku) {}

    private record Level(int onHand, int reserved) {}

    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Key, Level> levels = new HashMap<>();
    private final Map<ReservationId, Reservation> reservations = new LinkedHashMap<>();
    private @Nullable RuntimeException nextReserveFailure;
    private final Map<ReservationId, RuntimeException> releaseFailures = new HashMap<>();
    private @Nullable Runnable afterRollbackOfRefusal;

    public FakeInventory(Clock clock, PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.transactionManager = transactionManager;
    }

    public void stock(StoreId store, Sku sku, int onHand) {
        locked(() -> levels.put(new Key(store, sku), new Level(onHand, 0)));
    }

    public int available(StoreId store, Sku sku) {
        return locked(() -> {
            Level level = levels.getOrDefault(new Key(store, sku), new Level(0, 0));
            return level.onHand() - level.reserved();
        });
    }

    /** Every reservation ever made for {@code reference} and not rolled back, in creation order. */
    public List<Reservation> reservationsFor(String reference) {
        return locked(() -> reservations.values().stream()
                .filter(reservation -> reservation.reference().equals(reference))
                .toList());
    }

    /** The next {@link #reserve} fails with {@code failure}, as a database outage would. */
    public void failNextReserve(RuntimeException failure) {
        locked(() -> nextReserveFailure = failure);
    }

    /**
     * From now on, refusing a late confirmation also marks the caller's transaction rollback-only, as a
     * {@code @Transactional} implementation without {@code noRollbackFor} would; once that transaction has rolled back,
     * {@code afterRollback} runs (on the caller's thread, with the transaction already gone).
     */
    public void refuseLateConfirmationsByRollingBackTheCaller(Runnable afterRollback) {
        locked(() -> afterRollbackOfRefusal = afterRollback);
    }

    /** Releasing this reservation fails with {@code failure} until {@link #reset()}. */
    public void failReleasesOf(ReservationId reservationId, RuntimeException failure) {
        locked(() -> releaseFailures.put(reservationId, failure));
    }

    public void reset() {
        locked(() -> {
            levels.clear();
            reservations.clear();
            releaseFailures.clear();
            nextReserveFailure = null;
            afterRollbackOfRefusal = null;
            return null;
        });
    }

    @Override
    public ReservationResult reserve(ReservationRequest request) {
        return locked(() -> {
            RuntimeException failure = nextReserveFailure;
            if (failure != null) {
                nextReserveFailure = null;
                throw failure;
            }
            Optional<Reservation> existing = reservations.values().stream()
                    .filter(reservation -> reservation.reference().equals(request.reference()) && reservation.active())
                    .findFirst();
            if (existing.isPresent()) {
                return new ReservationResult.Reserved(
                        existing.get().id(), existing.get().expiresAt());
            }
            List<Shortage> shortages = new ArrayList<>();
            for (SkuQuantity line : request.lines()) {
                int available = available(request.storeId(), line.sku());
                if (available < line.quantity()) {
                    shortages.add(new Shortage(line.sku(), line.quantity(), available));
                }
            }
            if (!shortages.isEmpty()) {
                return new ReservationResult.Rejected(shortages);
            }
            Reservation held = new Reservation(
                    ReservationId.newId(),
                    request.storeId(),
                    request.reference(),
                    request.lines(),
                    clock.instant().plus(request.holdFor()),
                    Status.HELD,
                    null);
            change(null, held, +1);
            return new ReservationResult.Reserved(held.id(), held.expiresAt());
        });
    }

    @Override
    public void confirm(ReservationId reservationId) {
        locked(() -> {
            Reservation reservation = existing(reservationId);
            switch (reservation.status()) {
                case CONFIRMED -> {}
                case RELEASED, EXPIRED ->
                    throw new ReservationNoLongerHeldException(
                            reservationId, reservation.status().name());
                case HELD -> {
                    if (!clock.instant().isBefore(reservation.expiresAt())) {
                        // A late confirm must never succeed just because no sweeper has run yet.
                        change(reservation, reservation.becoming(Status.EXPIRED, null), -1);
                        if (afterRollbackOfRefusal != null) {
                            markCallerRollbackOnly(afterRollbackOfRefusal);
                        }
                        throw new ReservationNoLongerHeldException(reservationId, Status.EXPIRED.name());
                    }
                    change(reservation, reservation.becoming(Status.CONFIRMED, null), 0);
                }
            }
            return null;
        });
    }

    @Override
    public void release(ReservationId reservationId, ReleaseReason reason) {
        locked(() -> {
            RuntimeException failure = releaseFailures.get(reservationId);
            if (failure != null) {
                throw failure;
            }
            Reservation reservation = existing(reservationId);
            if (reservation.active()) {
                change(reservation, reservation.becoming(Status.RELEASED, reason), -1);
            }
            return null;
        });
    }

    private void markCallerRollbackOnly(Runnable afterRollback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    afterRollback.run();
                }
            }
        });
        // A participating scope that ends rollback-only marks the whole transaction so, as an exception leaving a
        // @Transactional participant does.
        new TransactionTemplate(transactionManager).executeWithoutResult(TransactionStatus::setRollbackOnly);
    }

    private Reservation existing(ReservationId id) {
        Reservation reservation = reservations.get(id);
        if (reservation == null) {
            throw new IllegalArgumentException("No reservation " + id);
        }
        return reservation;
    }

    /**
     * Moves a reservation to {@code next} and holds ({@code +1}) or returns ({@code -1}) its stock. Inside a
     * transaction the change is undone on rollback, by the inverse delta so that concurrent reservations of the same
     * items are left alone.
     */
    private void change(@Nullable Reservation previous, Reservation next, int direction) {
        apply(next, direction);
        reservations.put(next.id(), next);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        locked(() -> {
                            apply(next, -direction);
                            if (previous == null) {
                                reservations.remove(next.id());
                            } else {
                                reservations.put(previous.id(), previous);
                            }
                            return null;
                        });
                    }
                }
            });
        }
    }

    private void apply(Reservation reservation, int direction) {
        for (SkuQuantity line : reservation.lines()) {
            Key key = new Key(reservation.storeId(), line.sku());
            Level level = levels.getOrDefault(key, new Level(0, 0));
            levels.put(key, new Level(level.onHand(), level.reserved() + direction * line.quantity()));
        }
    }

    private <T> T locked(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
