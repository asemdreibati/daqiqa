package io.daqiqa.inventory.application;

import io.daqiqa.contracts.events.LineItemV1;
import io.daqiqa.contracts.events.ReservationExpiredV1;
import io.daqiqa.inventory.api.InventoryReservations;
import io.daqiqa.inventory.api.ReleaseReason;
import io.daqiqa.inventory.api.ReservationNoLongerHeldException;
import io.daqiqa.inventory.api.ReservationRequest;
import io.daqiqa.inventory.api.ReservationResult;
import io.daqiqa.inventory.api.Shortage;
import io.daqiqa.inventory.application.InventoryMetrics.ExpiryTrigger;
import io.daqiqa.inventory.application.InventoryMetrics.ReserveOutcome;
import io.daqiqa.inventory.domain.ConfirmOutcome;
import io.daqiqa.inventory.domain.EndReason;
import io.daqiqa.inventory.domain.HeldUnits;
import io.daqiqa.inventory.domain.ReleaseOutcome;
import io.daqiqa.inventory.domain.Reservation;
import io.daqiqa.inventory.domain.ReservationRepository;
import io.daqiqa.inventory.domain.ReservationStatus;
import io.daqiqa.inventory.domain.Shortfall;
import io.daqiqa.inventory.domain.StockLevelRepository;
import io.daqiqa.inventory.domain.StoreStock;
import io.daqiqa.inventory.domain.Transition;
import io.daqiqa.kernel.Ids;
import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.messaging.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds stock for orders so that a store never has more units reserved than on hand (ADR-0005), and returns it to sale
 * when orders fail, are cancelled or never complete.
 *
 * <p>Every method joins the caller's transaction when there is one (propagation {@code REQUIRED}), so an order and its
 * reservation commit or roll back together, and starts its own otherwise.
 *
 * <p>Locks are always taken in the same order, so concurrent calls queue instead of deadlocking: confirming, releasing
 * and expiring lock the reservation row first and its stock rows second; reserving locks only stock rows before it
 * inserts a new reservation; and any statement that locks several stock rows locks them by store, then SKU.
 */
@Service
public class ReservationService implements InventoryReservations {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservations;
    private final StockLevelRepository stockLevels;
    private final OutboxWriter outbox;
    private final InventoryMetrics metrics;
    private final Clock clock;

    ReservationService(
            ReservationRepository reservations,
            StockLevelRepository stockLevels,
            OutboxWriter outbox,
            InventoryMetrics metrics,
            Clock clock) {
        this.reservations = reservations;
        this.stockLevels = stockLevels;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <ol>
     *   <li>An active reservation for the reference is returned as it is, before any lock is taken: retries are cheap.
     *   <li>The basket's stock rows are locked in SKU order, so baskets sharing SKUs queue instead of deadlocking.
     *   <li>Shortfalls are computed from the locked rows. Any shortfall rejects the whole basket, having written
     *       nothing.
     *   <li>The reservation is inserted unless its reference was taken in the meantime ({@code INSERT ... ON CONFLICT DO
     *       NOTHING}: a unique violation would abort the caller's whole transaction).
     *   <li>The lines are added to the reserved quantities.
     * </ol>
     *
     * <p><b>Two first calls with the same reference</b> can both pass step 1. If their baskets share stock rows, the
     * second waits in step 2 until the first commits, then finds the first call's units reserved: a shortfall that is
     * really its own reservation. So a shortfall looks for the reference again before rejecting. If the baskets share
     * no rows, the second waits in step 4 on the reference's unique index until the first commits, and then returns the
     * first call's reservation. Either way exactly one reservation holds stock, and both calls return it.
     */
    @Override
    @Transactional
    public ReservationResult reserve(ReservationRequest request) {
        return metrics.timeReserve(() -> hold(request));
    }

    private ReservationResult hold(ReservationRequest request) {
        Optional<Reservation> existing = reservations.findActiveByReference(request.reference());
        if (existing.isPresent()) {
            return replayed(existing.get());
        }
        StoreStock stock = stockLevels.lockForUpdate(
                request.storeId(),
                request.lines().stream().map(SkuQuantity::sku).toList());
        List<Shortfall> shortfalls = stock.shortfalls(request.lines());
        if (!shortfalls.isEmpty()) {
            return reservations
                    .findActiveByReference(request.reference())
                    .map(this::replayed)
                    .orElseGet(() -> rejected(shortfalls));
        }
        Instant now = Timestamps.now(clock);
        Reservation reservation = Reservation.hold(
                ReservationId.newId(),
                request.storeId(),
                request.reference(),
                request.lines(),
                now,
                Timestamps.storable(request.holdFor()));
        if (!reservations.insertIfReferenceFree(reservation, now)) {
            return replayed(reservations
                    .findActiveByReference(request.reference())
                    .orElseThrow(() -> new IllegalStateException("The reservation holding reference "
                            + request.reference() + " ended while this call was waiting for it; retry")));
        }
        stockLevels.reserve(reservation.storeId(), reservation.lines(), now);
        metrics.reservation(ReserveOutcome.RESERVED);
        return new ReservationResult.Reserved(reservation.id(), reservation.expiresAt());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A held reservation whose time has run out is expired here (its stock returned, {@code ReservationExpiredV1}
     * appended) and the confirmation fails, even if the sweeper has not run yet: a late confirmation must never
     * succeed. That expiry is kept although the method throws: {@link ReservationNoLongerHeldException} does not mark
     * the transaction rollback-only, so a caller that catches it (and abandons its order) still commits.
     *
     * @throws ReservationNoLongerHeldException if the reservation was released or expired, or its hold ran out
     * @throws IllegalArgumentException if there is no reservation with this id
     */
    @Override
    @Transactional(noRollbackFor = ReservationNoLongerHeldException.class)
    public void confirm(ReservationId reservationId) {
        Reservation reservation = lockExisting(reservationId);
        Instant now = Timestamps.now(clock);
        switch (reservation.confirm(now)) {
            case ConfirmOutcome.Confirmed(Transition confirmation) -> reservations.apply(List.of(confirmation), now);
            case ConfirmOutcome.AlreadyConfirmed() -> log.debug("Reservation {} was already confirmed", reservationId);
            case ConfirmOutcome.HoldElapsed(Transition expiry) -> {
                expire(List.of(expiry), now, ExpiryTrigger.LATE_CONFIRM);
                log.info(
                        "Refused to confirm reservation {}: its hold ran out at {}; the stock is back on sale",
                        reservationId,
                        reservation.expiresAt());
                throw noLongerHeld(reservationId, expiry.to().status());
            }
            case ConfirmOutcome.NoLongerHeld(ReservationStatus status) -> throw noLongerHeld(reservationId, status);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Takes the reservation's row lock, then its stock rows' locks. Release reservations one per transaction: a
     * transaction releasing several would lock their stock rows out of (store, SKU) order and could deadlock with a
     * concurrent reservation.
     *
     * @throws IllegalArgumentException if there is no reservation with this id
     */
    @Override
    @Transactional
    public void release(ReservationId reservationId, ReleaseReason reason) {
        Reservation reservation = lockExisting(reservationId);
        switch (reservation.release(endReasonFor(reason))) {
            case ReleaseOutcome.Released(Transition release) -> {
                Instant now = Timestamps.now(clock);
                reservations.apply(List.of(release), now);
                stockLevels.release(HeldUnits.heldBy(List.of(release.to())), now); // hot rows last, see expire()
                metrics.released(reason);
            }
            case ReleaseOutcome.AlreadyEnded(ReservationStatus status) ->
                log.debug("Reservation {} was already {}; nothing to release", reservationId, status);
        }
    }

    /**
     * Expires up to {@code limit} holds that ran out by {@code now}, in one transaction, and returns how many.
     *
     * <p>Holds locked by another transaction (being confirmed or released right now, or taken by another instance's
     * sweep) are skipped rather than waited for ({@code FOR UPDATE SKIP LOCKED}): instances sweep side by side without
     * blocking each other or a checkout, and no hold is expired twice. The stock of the whole batch is returned with
     * one statement, its rows locked in (store, SKU) order.
     */
    @Transactional
    public int expireElapsedHolds(Instant now, int limit) {
        List<Transition> expiries = reservations.lockElapsedHolds(now, limit).stream()
                .map(reservation -> reservation.expire(now))
                .toList();
        if (!expiries.isEmpty()) {
            expire(expiries, now, ExpiryTrigger.SWEEPER);
        }
        return expiries.size();
    }

    /**
     * Ends holds that ran out: status, event, stock back on sale.
     *
     * <p>The order keeps the hot stock rows (every checkout of those SKUs needs them) locked for as short a time as
     * possible: they are locked last, just before the commit. The guarded status update comes first. It touches only
     * reservation rows this transaction already holds, and a stale transition fails there before any other work. The
     * events come next: the outbox is an append-only table nobody else waits on. The lock order stays the global one:
     * reservation rows, then stock rows by store and SKU.
     */
    private void expire(List<Transition> expiries, Instant now, ExpiryTrigger trigger) {
        reservations.apply(expiries, now);
        List<Reservation> expired = expiries.stream().map(Transition::to).toList();
        expired.forEach(reservation -> outbox.append(expiredEvent(reservation, now)));
        stockLevels.release(HeldUnits.heldBy(expired), now);
        metrics.expired(expired.size(), trigger);
    }

    private Reservation lockExisting(ReservationId reservationId) {
        return reservations
                .lockById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("There is no reservation " + reservationId));
    }

    private ReservationResult replayed(Reservation reservation) {
        metrics.reservation(ReserveOutcome.REPLAYED);
        return new ReservationResult.Reserved(reservation.id(), reservation.expiresAt());
    }

    private ReservationResult rejected(List<Shortfall> shortfalls) {
        metrics.reservation(ReserveOutcome.REJECTED);
        return new ReservationResult.Rejected(shortfalls.stream()
                .map(shortfall -> new Shortage(shortfall.sku(), shortfall.requested(), shortfall.available()))
                .toList());
    }

    /** Exhaustive on purpose: a reason added to the API does not compile until the domain knows it too. */
    private static EndReason endReasonFor(ReleaseReason reason) {
        return switch (reason) {
            case ORDER_CANCELLED -> EndReason.ORDER_CANCELLED;
            case PAYMENT_FAILED -> EndReason.PAYMENT_FAILED;
            case ORDER_ABANDONED -> EndReason.ORDER_ABANDONED;
            case EXPIRED -> EndReason.EXPIRED;
            case OPERATOR -> EndReason.OPERATOR;
        };
    }

    private static ReservationNoLongerHeldException noLongerHeld(ReservationId id, ReservationStatus status) {
        return new ReservationNoLongerHeldException(id, status.name());
    }

    private static ReservationExpiredV1 expiredEvent(Reservation reservation, Instant now) {
        return new ReservationExpiredV1(
                Ids.newTimeOrderedId(),
                now,
                reservation.id().value(),
                reservation.storeId().value(),
                reservation.reference(),
                reservation.lines().stream()
                        .map(line -> new LineItemV1(line.sku().value(), line.quantity()))
                        .toList());
    }
}
