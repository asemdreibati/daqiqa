package io.daqiqa.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.daqiqa.messaging.outbox.EventPublicationException;
import io.daqiqa.messaging.outbox.MessagingProperties;
import io.daqiqa.messaging.outbox.MessagingProperties.Kafka;
import io.daqiqa.messaging.outbox.MessagingProperties.Publisher;
import io.daqiqa.messaging.outbox.MessagingProperties.Purge;
import io.daqiqa.messaging.outbox.MessagingProperties.Relay;
import io.daqiqa.messaging.outbox.PendingEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** The Kafka adapter's failure paths; the happy path runs against a real broker in {@code OutboxToKafkaTest}. */
class KafkaEventPublisherTest {

    @Test
    void reportsAnEventTheBrokerNeverAcknowledgedAsAFailedPublication() {
        DefaultKafkaProducerFactory<String, String> unreachable = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                200));
        KafkaEventPublisher publisher =
                new KafkaEventPublisher(new KafkaTemplate<>(unreachable), sendTimeout(Duration.ofMillis(500)));
        PendingEvent event = new PendingEvent(
                UUID.randomUUID(),
                1,
                "daqiqa.test.happened.v1",
                "daqiqa.test.v1",
                "DXB-MARINA",
                "{}",
                Instant.EPOCH,
                0);

        try {
            assertThatThrownBy(() -> publisher.publish(event))
                    .isInstanceOf(EventPublicationException.class)
                    .hasMessageContaining(event.id().toString());
        } finally {
            unreachable.destroy();
        }
    }

    @Test
    void acceptsOnlyProducersThatNeitherLoseNorReorderAcknowledgedEvents() {
        assertThatNoException().isThrownBy(() -> KafkaEventPublisher.requireDurableProducer(Map.of()));
        assertThatNoException()
                .isThrownBy(() -> KafkaEventPublisher.requireDurableProducer(
                        Map.of(ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")));
        assertThatIllegalStateException()
                .isThrownBy(() -> KafkaEventPublisher.requireDurableProducer(Map.of(ProducerConfig.ACKS_CONFIG, "1")));
        assertThatIllegalStateException()
                .isThrownBy(() -> KafkaEventPublisher.requireDurableProducer(
                        Map.of(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false)));
    }

    private static MessagingProperties sendTimeout(Duration timeout) {
        return new MessagingProperties(
                Publisher.KAFKA,
                new Relay(Duration.ofMillis(250), 100),
                Duration.ofDays(7),
                new Purge(Duration.ofHours(1), 1000),
                new Kafka(timeout));
    }
}
