package io.daqiqa.promise.domain;

import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.util.Objects;

/**
 * Outbound port: the safety margin added to every ETA at a store, so that promises hold despite the variance the model
 * does not capture. Version 1 is one constant; wave 3 learns it per store from how late past deliveries were.
 */
public interface PromiseCalibration {

    Duration bufferAt(StoreId store);

    /** The same buffer at every store. */
    static PromiseCalibration constant(Duration buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.isNegative()) {
            throw new IllegalArgumentException("The buffer cannot be negative, was " + buffer);
        }
        return store -> buffer;
    }
}
