package io.daqiqa.inventory.api;

import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Hold stock at one store for one reference (usually an order id).
 *
 * @param storeId store to reserve at (quick commerce fulfils a basket from a single store)
 * @param reference caller's idempotency reference: reserving twice for the same reference returns the same
 *     reservation instead of holding stock twice
 * @param lines what to hold; duplicate SKUs are merged
 * @param holdFor how long the hold lasts before it expires and the stock returns to sale
 */
public record ReservationRequest(StoreId storeId, String reference, List<SkuQuantity> lines, Duration holdFor) {

    public static final Duration MIN_HOLD = Duration.ofSeconds(30);
    public static final Duration MAX_HOLD = Duration.ofMinutes(30);

    public ReservationRequest {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(holdFor, "holdFor");
        if (reference.isBlank() || reference.length() > 64) {
            throw new IllegalArgumentException("reference must be 1..64 characters");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a reservation needs at least one line");
        }
        if (holdFor.compareTo(MIN_HOLD) < 0 || holdFor.compareTo(MAX_HOLD) > 0) {
            throw new IllegalArgumentException("holdFor must be between " + MIN_HOLD + " and " + MAX_HOLD);
        }
        lines = SkuQuantity.merge(lines);
    }
}
