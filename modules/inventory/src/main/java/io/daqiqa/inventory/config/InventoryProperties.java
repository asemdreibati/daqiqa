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
     * @param lockTimeout how long a sweep waits for a stock row a checkout is holding before giving up and leaving
     *     those holds to the next round ({@code daqiqa.inventory.expiry.lock-timeout}, default 500ms). Well below the
     *     application-wide lock timeout, so a hot row never keeps a scheduler thread.
     */
    record Expiry(
            @DefaultValue("5s") Duration interval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("500ms") Duration lockTimeout) {

        Expiry {
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException(
                        "daqiqa.inventory.expiry.interval must be positive, was " + interval);
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException(
                        "daqiqa.inventory.expiry.batch-size must be positive, was " + batchSize);
            }
            if (lockTimeout.isNegative() || lockTimeout.isZero()) {
                throw new IllegalArgumentException(
                        "daqiqa.inventory.expiry.lock-timeout must be positive, was " + lockTimeout);
            }
        }
    }
}
