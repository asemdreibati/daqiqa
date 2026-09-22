package io.daqiqa.inventory.application;

import io.daqiqa.inventory.domain.AdjustmentReason;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import java.util.Objects;

/** Command: store operations changed the quantity of {@code sku} on hand at {@code storeId} by {@code delta}. */
public record AdjustStock(StoreId storeId, Sku sku, int delta, AdjustmentReason reason) {

    /** Largest change one adjustment may make, either way (a sanity bound against typos: 10000 instead of 100). */
    public static final int MAX_DELTA = 10_000;

    public AdjustStock {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(reason, "reason");
        if (delta == 0 || Math.abs(delta) > MAX_DELTA) {
            throw new IllegalArgumentException("delta must be non-zero and within ±" + MAX_DELTA + ", was " + delta);
        }
    }
}
