package io.daqiqa.messaging.outbox;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the transactional outbox, bound from {@code daqiqa.messaging.*}. Every value has a default.
 *
 * @param publisher where the relay publishes: {@code kafka} (the default; the {@code spring.kafka.producer} settings
 *     must then be explicitly durable, see the Kafka publisher) or {@code logging}, an explicit opt-in for local runs
 *     without a broker. Missing configuration must never make the relay mark events published that went nowhere. The
 *     value selects the {@link EventPublisher} bean; it is bound here too so that a typo fails at startup
 * @param relay how often and how much the relay publishes
 * @param retention how long published events are kept before the purge deletes them
 * @param purge how often and in what batches published events are deleted
 * @param kafka settings of the Kafka publisher
 */
@ConfigurationProperties("daqiqa.messaging")
public record MessagingProperties(
        @DefaultValue("kafka") Publisher publisher,
        @DefaultValue Relay relay,
        @DefaultValue("7d") Duration retention,
        @DefaultValue Purge purge,
        @DefaultValue Kafka kafka) {

    public MessagingProperties {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(relay, "relay");
        Objects.requireNonNull(purge, "purge");
        Objects.requireNonNull(kafka, "kafka");
        requirePositive(retention, "daqiqa.messaging.retention");
    }

    /** Where the relay publishes events. */
    public enum Publisher {
        LOGGING,
        KAFKA
    }

    /**
     * @param interval pause between two relay rounds ({@code daqiqa.messaging.relay.interval})
     * @param batchSize maximum number of events published per round
     * @param initialBackoff wait before retrying an event whose publication failed once; doubled at every further
     *     failure
     * @param maxBackoff the longest wait between two attempts at a failing event
     */
    public record Relay(
            @DefaultValue(Relay.DEFAULT_INTERVAL) Duration interval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("1s") Duration initialBackoff,
            @DefaultValue("5m") Duration maxBackoff) {

        /** Shared with the {@code @Scheduled} placeholder so the two defaults cannot drift apart. */
        public static final String DEFAULT_INTERVAL = "250ms";

        public Relay {
            requirePositive(interval, "daqiqa.messaging.relay.interval");
            requirePositive(batchSize, "daqiqa.messaging.relay.batch-size");
            requirePositive(initialBackoff, "daqiqa.messaging.relay.initial-backoff");
            requirePositive(maxBackoff, "daqiqa.messaging.relay.max-backoff");
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                        "daqiqa.messaging.relay.max-backoff must not be shorter than the initial backoff");
            }
        }
    }

    /**
     * @param interval pause between two purges ({@code daqiqa.messaging.purge.interval})
     * @param batchSize rows deleted per statement, so a large purge never becomes one long transaction
     */
    public record Purge(
            @DefaultValue(Purge.DEFAULT_INTERVAL) Duration interval,
            @DefaultValue("1000") int batchSize) {

        /** Shared with the {@code @Scheduled} placeholder so the two defaults cannot drift apart. */
        public static final String DEFAULT_INTERVAL = "1h";

        public Purge {
            requirePositive(interval, "daqiqa.messaging.purge.interval");
            requirePositive(batchSize, "daqiqa.messaging.purge.batch-size");
        }
    }

    /**
     * @param sendTimeout how long the relay waits for the broker to acknowledge one event before counting it as failed
     */
    public record Kafka(@DefaultValue("10s") Duration sendTimeout) {

        public Kafka {
            requirePositive(sendTimeout, "daqiqa.messaging.kafka.send-timeout");
        }
    }

    private static void requirePositive(Duration value, String property) {
        Objects.requireNonNull(value, property);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(property + " must be positive, was " + value);
        }
    }

    private static void requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive, was " + value);
        }
    }
}
