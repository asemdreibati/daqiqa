package io.daqiqa.inventory.domain;

/**
 * Where a reservation is in its life.
 *
 * <pre>
 *   HELD ──confirm──▶ CONFIRMED ──release──▶ RELEASED
 *    │ └───────────────release───────────────▲
 *    └──hold elapsed (sweeper, or a late confirm)──▶ EXPIRED
 * </pre>
 */
public enum ReservationStatus {
    /** Stock is held until the reservation's expiry time, waiting for the order to be confirmed. */
    HELD,
    /** The order was accepted: stock stays held and the hold no longer expires. */
    CONFIRMED,
    /** The stock went back on sale on request (order cancelled, payment failed, ...). */
    RELEASED,
    /** Nobody confirmed the hold in time; the stock went back on sale. */
    EXPIRED;

    /** Whether reservations in this status count towards {@link StockLevel#reserved()}. */
    public boolean holdsStock() {
        return this == HELD || this == CONFIRMED;
    }
}
