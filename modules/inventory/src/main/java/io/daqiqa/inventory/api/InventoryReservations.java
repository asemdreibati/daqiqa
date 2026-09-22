package io.daqiqa.inventory.api;

import io.daqiqa.kernel.ReservationId;

/**
 * The strongly consistent side of inventory: holding stock for orders.
 *
 * <p>Guarantees, whatever the concurrency: a store never has more units reserved than it has on hand, and a
 * multi-line reservation is all-or-nothing.
 */
public interface InventoryReservations {

    /**
     * Atomically holds every line at the store, or nothing.
     *
     * <p>Joins the caller's transaction when there is one, so an order and its reservation commit or roll back
     * together. Idempotent by {@link ReservationRequest#reference()}: if a held or confirmed reservation already exists
     * for the reference, it is returned unchanged.
     */
    ReservationResult reserve(ReservationRequest request);

    /** The order was accepted: the hold no longer expires. Idempotent. */
    void confirm(ReservationId reservationId);

    /** Puts held stock back on sale. Idempotent: releasing an already released or expired reservation does nothing. */
    void release(ReservationId reservationId, ReleaseReason reason);
}
