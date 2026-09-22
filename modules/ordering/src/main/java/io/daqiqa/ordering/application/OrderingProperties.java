package io.daqiqa.ordering.application;

import io.daqiqa.inventory.api.ReservationRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the ordering context, bound from {@code daqiqa.ordering.*}. Every value has a default, so the
 * application starts without any configuration.
 *
 * @param hold how long a checkout holds the basket's stock while the payment completes; also how long an order may
 *     wait for its payment before the sweeper abandons it
 * @param idempotency how checkout requests are deduplicated (ADR-0008)
 * @param sweeper how abandoned orders are found
 * @param payment behaviour of the simulated payment gateway
 */
@ConfigurationProperties("daqiqa.ordering")
public record OrderingProperties(
        @DefaultValue("5m") Duration hold,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Sweeper sweeper,
        @DefaultValue Payment payment) {

    public OrderingProperties {
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(sweeper, "sweeper");
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(hold, "hold");
        if (hold.compareTo(ReservationRequest.MIN_HOLD) < 0 || hold.compareTo(ReservationRequest.MAX_HOLD) > 0) {
            throw new IllegalArgumentException("daqiqa.ordering.hold must be between " + ReservationRequest.MIN_HOLD
                    + " and " + ReservationRequest.MAX_HOLD + ", was " + hold);
        }
    }

    /**
     * @param lease how long a claimed key belongs to the request processing it. A retry arriving later takes the
     *     claim over, presuming that request's instance died; keep it well above a normal checkout, payment included
     * @param retention how long a key and its stored response are kept (ADR-0008: 24 hours)
     * @param purgeInterval pause between two purges of expired keys
     * @param purgeBatchSize keys deleted per statement
     */
    public record Idempotency(
            @DefaultValue("60s") Duration lease,
            @DefaultValue("24h") Duration retention,

            @DefaultValue(Idempotency.DEFAULT_PURGE_INTERVAL)
            Duration purgeInterval,

            @DefaultValue("1000") int purgeBatchSize) {

        /** Shared with the {@code @Scheduled} placeholder so the two defaults cannot drift apart. */
        public static final String DEFAULT_PURGE_INTERVAL = "1h";

        public Idempotency {
            requirePositive(lease, "daqiqa.ordering.idempotency.lease");
            requirePositive(retention, "daqiqa.ordering.idempotency.retention");
            requirePositive(purgeInterval, "daqiqa.ordering.idempotency.purge-interval");
            if (retention.compareTo(lease) <= 0) {
                throw new IllegalArgumentException(
                        "daqiqa.ordering.idempotency.retention must be longer than the lease");
            }
            if (purgeBatchSize <= 0) {
                throw new IllegalArgumentException("daqiqa.ordering.idempotency.purge-batch-size must be positive");
            }
        }
    }

    /**
     * @param interval pause between two sweeps for abandoned orders
     * @param batchSize orders examined per sweep
     */
    public record Sweeper(
            @DefaultValue(Sweeper.DEFAULT_INTERVAL) Duration interval,
            @DefaultValue("100") int batchSize) {

        /** Shared with the {@code @Scheduled} placeholder so the two defaults cannot drift apart. */
        public static final String DEFAULT_INTERVAL = "30s";

        public Sweeper {
            requirePositive(interval, "daqiqa.ordering.sweeper.interval");
            if (batchSize <= 0) {
                throw new IllegalArgumentException("daqiqa.ordering.sweeper.batch-size must be positive");
            }
        }
    }

    /**
     * The simulated payment gateway, v1's only one: deterministic, for tests and demos.
     *
     * @param declineAbove amounts above this, in major units of the order's currency (1000.00 = 1,000 AED), are declined
     * @param latency how long each authorization takes, to rehearse a slow provider
     */
    public record Payment(
            @DefaultValue("1000.00") BigDecimal declineAbove,
            @DefaultValue("0s") Duration latency) {

        public Payment {
            Objects.requireNonNull(declineAbove, "daqiqa.ordering.payment.decline-above");
            Objects.requireNonNull(latency, "daqiqa.ordering.payment.latency");
            if (declineAbove.signum() < 0 || latency.isNegative()) {
                throw new IllegalArgumentException("daqiqa.ordering.payment.* must not be negative");
            }
        }
    }

    private static void requirePositive(Duration value, String property) {
        Objects.requireNonNull(value, property);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be positive, was " + value);
        }
    }
}
