package io.daqiqa.promise.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a basket waits at a store before it leaves.
 *
 * @param pickerQueue waiting for a picker to start on the basket
 * @param riderWait waiting for a rider to be free once the basket is packed
 */
public record StoreLoad(Duration pickerQueue, Duration riderWait) {

    public StoreLoad {
        Objects.requireNonNull(pickerQueue, "pickerQueue");
        Objects.requireNonNull(riderWait, "riderWait");
        if (pickerQueue.isNegative() || riderWait.isNegative()) {
            throw new IllegalArgumentException("Waits cannot be negative: " + pickerQueue + ", " + riderWait);
        }
    }
}
