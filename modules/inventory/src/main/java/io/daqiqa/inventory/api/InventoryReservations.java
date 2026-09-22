package io.daqiqa.inventory.api;

import io.daqiqa.kernel.ReservationId;

/**
 * The strongly consistent side of inventory: holding stock for orders.
 *
 * <p>Guarantees, whatever the concurrency: a store never has more units reserved than it has on hand, and a
 * multi-line reservation is all-or-nothing.
 *
 * <p><b>Rules for callers.</b> Every method joins the caller's transaction when there is one, and starts its own
 * otherwise. The guarantees hold when that transaction follows these rules:
 *
 * <ul>
 *   <li><b>READ COMMITTED isolation</b> (PostgreSQL's default). The methods lock rows and then read their latest
 *       committed version. Under REPEATABLE READ or SERIALIZABLE, a concurrent change would fail the call with a
 *       serialization error instead of queueing behind it.
 *   <li><b>One basket reserved and one reservation released per transaction.</b> Every call locks its stock rows in
 *       (store, SKU) order. A second basket or release in the same transaction takes a second set of locks out of
 *       that order, and can deadlock with concurrent checkouts.
 *   <li><b>Keep the transaction short, and reserve last.</b> Row locks are held until the caller commits, even when
 *       {@link #reserve} returns {@link ReservationResult.Rejected}. Every checkout of those SKUs at that store waits
 *       for the caller's transaction, so slow work (a payment call, say) belongs outside it.
 * </ul>
 */
public interface InventoryReservations {

    /**
     * Atomically holds every line at the store, or nothing.
     *
     * <p>Joins the caller's transaction when there is one, so an order and its reservation commit or roll back
     * together. Idempotent by {@link ReservationRequest#reference()}: if a held or confirmed reservation already exists
     * for the reference, it is returned unchanged. The one exception is a hold whose time has run out: it expires, and
     * the basket is reserved afresh (or rejected if the stock is now short).
     */
    ReservationResult reserve(ReservationRequest request);

    /**
     * The order was accepted: the hold no longer expires. Idempotent.
     *
     * @throws ReservationNoLongerHeldException if the reservation was released or expired, or if its hold ran out, in
     *     which case it expires right here, whether or not the sweeper has run. This exception does <b>not</b> mark the
     *     caller's transaction rollback-only. A caller may catch it, record the outcome (abandon its order) and
     *     commit, and the expiry is committed with it.
     */
    void confirm(ReservationId reservationId);

    /**
     * Puts held stock back on sale. Idempotent: releasing an already released or expired reservation does nothing.
     * Release one reservation per transaction (see the rules above).
     */
    void release(ReservationId reservationId, ReleaseReason reason);
}
