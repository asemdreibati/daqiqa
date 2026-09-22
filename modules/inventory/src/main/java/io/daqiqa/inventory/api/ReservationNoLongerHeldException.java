package io.daqiqa.inventory.api;

import io.daqiqa.kernel.ReservationId;

/**
 * Thrown by {@link InventoryReservations#confirm(ReservationId)} when the hold already expired or was released: the
 * stock may have been sold to someone else, so the order must not be confirmed.
 */
public final class ReservationNoLongerHeldException extends RuntimeException {

    private final ReservationId reservationId;

    public ReservationNoLongerHeldException(ReservationId reservationId, String status) {
        super("Reservation " + reservationId + " is no longer held (status " + status + ")");
        this.reservationId = reservationId;
    }

    public ReservationId reservationId() {
        return reservationId;
    }
}
