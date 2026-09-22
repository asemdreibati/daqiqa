package io.daqiqa.messaging.publisher;

import io.daqiqa.messaging.outbox.EventPublicationException;
import io.daqiqa.messaging.outbox.EventPublisher;
import io.daqiqa.messaging.outbox.MessagingProperties;
import io.daqiqa.messaging.outbox.PendingEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka: the default publisher ({@code daqiqa.messaging.publisher=kafka}, or unset).
 *
 * <p>The record key is the event's partition key (the store id for store-scoped facts), so each store's events land
 * in one partition, in order. The value is the event JSON; CloudEvents attributes travel as headers. Each send waits
 * for the broker's acknowledgement, bounded by {@code daqiqa.messaging.kafka.send-timeout}, because the relay may mark
 * an event published only once it is durable.
 *
 * <p>The producer comes from Spring Boot ({@code spring.kafka.*}). The constructor refuses one that is not explicitly
 * configured to keep acknowledged events, in order, without stalling the relay (see {@link #requireDurableProducer}).
 */
@Component
@ConditionalOnProperty(name = "daqiqa.messaging.publisher", havingValue = "kafka", matchIfMissing = true)
class KafkaEventPublisher implements EventPublisher {

    /** The most in-flight requests with which an idempotent producer still keeps each partition in order. */
    static final int MAX_IN_FLIGHT = 5;

    private final KafkaTemplate<String, String> kafka;
    private final Duration sendTimeout;

    KafkaEventPublisher(KafkaTemplate<String, String> kafka, MessagingProperties properties) {
        this.sendTimeout = properties.kafka().sendTimeout();
        requireDurableProducer(kafka.getProducerFactory().getConfigurationProperties(), sendTimeout);
        this.kafka = kafka;
    }

    @Override
    public void publish(PendingEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.topic(), null, event.partitionKey(), event.payload(), CloudEventHeaders.of(event));
        try {
            kafka.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublicationException(event, e);
        } catch (ExecutionException e) {
            throw new EventPublicationException(event, e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException | RuntimeException e) {
            // RuntimeException: the client can also fail synchronously, e.g. when the record is too large.
            throw new EventPublicationException(event, e);
        }
    }

    /**
     * Refuses a producer that could lose, reorder or stall. The settings must be explicit: Kafka's defaults have changed
     * between versions ({@code acks} was 1 before 3.0), and whether acknowledged events survive must not hang on one.
     *
     * <ul>
     *   <li>{@code acks=all}: an acknowledged event survives the loss of the partition leader;
     *   <li>{@code enable.idempotence=true}: retried batches are neither duplicated nor reordered within a partition;
     *   <li>{@code max.in.flight.requests.per.connection} at most 5 (the default): the most idempotence keeps in order;
     *   <li>{@code max.block.ms} at most the send timeout: {@code send} itself may block that long (fetching metadata,
     *       a full buffer) before the relay's timeout starts, holding the relay lock and stalling every later event.
     * </ul>
     */
    static void requireDurableProducer(Map<String, Object> config, Duration sendTimeout) {
        List<String> missing = new ArrayList<>();
        String acks = setting(config, ProducerConfig.ACKS_CONFIG);
        if (!"all".equals(acks) && !"-1".equals(acks)) {
            missing.add("spring.kafka.producer.acks=all (was " + acks + ")");
        }
        String idempotence = setting(config, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        if (!"true".equalsIgnoreCase(idempotence)) {
            missing.add("spring.kafka.producer.properties.enable.idempotence=true (was " + idempotence + ")");
        }
        String inFlight = setting(config, ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
        if (inFlight != null && Integer.parseInt(inFlight) > MAX_IN_FLIGHT) {
            missing.add("spring.kafka.producer.properties.max.in.flight.requests.per.connection at most "
                    + MAX_IN_FLIGHT + " (was " + inFlight + ")");
        }
        String maxBlock = setting(config, ProducerConfig.MAX_BLOCK_MS_CONFIG);
        if (maxBlock == null || Long.parseLong(maxBlock) > sendTimeout.toMillis()) {
            missing.add("spring.kafka.producer.properties.max.block.ms at most daqiqa.messaging.kafka.send-timeout, "
                    + sendTimeout.toMillis() + " ms (was " + maxBlock + ")");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The outbox publishes to Kafka only through a durable producer; set " + String.join(", ", missing)
                            + ". For local runs without a broker, set daqiqa.messaging.publisher=logging.");
        }
    }

    private static @Nullable String setting(Map<String, Object> config, String name) {
        Object value = config.get(name);
        return value == null ? null : String.valueOf(value).trim();
    }
}
