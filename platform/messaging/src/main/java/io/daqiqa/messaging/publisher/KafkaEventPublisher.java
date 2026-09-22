package io.daqiqa.messaging.publisher;

import io.daqiqa.messaging.outbox.EventPublicationException;
import io.daqiqa.messaging.outbox.EventPublisher;
import io.daqiqa.messaging.outbox.MessagingProperties;
import io.daqiqa.messaging.outbox.PendingEvent;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka (active when {@code daqiqa.messaging.publisher=kafka}).
 *
 * <p>The record key is the event's partition key (the store id for store-scoped facts), so each store's events land
 * in one partition, in order. The value is the event JSON; CloudEvents attributes travel as headers. Each send waits
 * for the broker's acknowledgement, bounded by {@code daqiqa.messaging.kafka.send-timeout}, because the relay may mark
 * an event published only once it is durable.
 *
 * <p>The producer comes from Spring Boot ({@code spring.kafka.*}) and must be idempotent with {@code acks=all}: the
 * constructor refuses a configuration that could lose acknowledged events or reorder retried ones.
 */
@Component
@ConditionalOnProperty(name = "daqiqa.messaging.publisher", havingValue = "kafka")
class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final Duration sendTimeout;

    KafkaEventPublisher(KafkaTemplate<String, String> kafka, MessagingProperties properties) {
        requireDurableProducer(kafka.getProducerFactory().getConfigurationProperties());
        this.kafka = kafka;
        this.sendTimeout = properties.kafka().sendTimeout();
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
     * Idempotence (with its default of at most five in-flight requests) keeps retried batches in order and free of
     * duplicates within a partition; {@code acks=all} means an acknowledged event survives the loss of the leader.
     * Kafka's own defaults are both, so only an explicit override is rejected.
     */
    static void requireDurableProducer(Map<String, Object> config) {
        Object acks = config.get(ProducerConfig.ACKS_CONFIG);
        if (acks != null && !"all".equals(String.valueOf(acks)) && !"-1".equals(String.valueOf(acks))) {
            throw new IllegalStateException(
                    "The outbox publisher needs spring.kafka.producer.acks=all, but acks is " + acks);
        }
        Object idempotence = config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        if (idempotence != null && !Boolean.parseBoolean(String.valueOf(idempotence))) {
            throw new IllegalStateException("The outbox publisher needs an idempotent producer, but "
                    + ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG + " is " + idempotence);
        }
    }
}
