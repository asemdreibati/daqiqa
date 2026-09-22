package io.daqiqa.inventory.api;

import io.daqiqa.kernel.ReservationId;
import java.time.Instant;
import java.util.List;

/** Outcome of a reservation attempt. Rejection is an expected business outcome, not an exception. */
public sealed interface ReservationResult {

    /** Every line is held until {@code expiresAt}. */
    record Reserved(ReservationId reservationId, Instant expiresAt) implements ReservationResult {}

    /** Nothing was held because at least one line could not be covered. */
    record Rejected(List<Shortage> shortages) implements ReservationResult {

        public Rejected {
            shortages = List.copyOf(shortages);
        }
    }
}
