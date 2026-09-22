package io.daqiqa.inventory.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code daqiqa.inventory}. Every one has a default, so the application starts without configuration.
 * Invalid values fail the startup.
 */
@ConfigurationProperties("daqiqa.inventory")
record InventoryProperties(@DefaultValue Expiry expiry) {

    /**
     * The sweep that returns holds nobody confirmed in time to sale.
     *
     * @param interval pause between the end of one sweep and the start of the next ({@code daqiqa.inventory.expiry
     *     .interval}, default 5s)
     * @param batchSize holds expired per transaction ({@code daqiqa.inventory.expiry.batch-size}, default 100)
     */
    record Expiry(
            @DefaultValue("5s") Duration interval,
            @DefaultValue("100") int batchSize) {

        Expiry {
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException(
                        "daqiqa.inventory.expiry.interval must be positive, was " + interval);
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException(
                        "daqiqa.inventory.expiry.batch-size must be positive, was " + batchSize);
            }
        }
    }
}
