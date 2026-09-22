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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** The Kafka adapter's failure paths; the happy path runs against a real broker in {@code OutboxToKafkaTest}. */
class KafkaEventPublisherTest {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final Map<String, Object> DURABLE = Map.of(
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true",
            ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");

    @Test
    void reportsAnEventTheBrokerNeverAcknowledgedAsAFailedPublication() {
        Map<String, Object> config = new HashMap<>(DURABLE);
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "200");
        DefaultKafkaProducerFactory<String, String> unreachable = new DefaultKafkaProducerFactory<>(config);
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
                0,
                null);

        try {
            assertThatThrownBy(() -> publisher.publish(event))
                    .isInstanceOf(EventPublicationException.class)
                    .hasMessageContaining(event.id().toString());
        } finally {
            unreachable.destroy();
        }
    }

    @Test
    void acceptsAnExplicitlyDurableProducerThatCannotStallTheRelay() {
        assertThatNoException().isThrownBy(() -> KafkaEventPublisher.requireDurableProducer(DURABLE, SEND_TIMEOUT));
        assertThatNoException()
                .isThrownBy(() -> KafkaEventPublisher.requireDurableProducer(
                        with(Map.of(
                                ProducerConfig.ACKS_CONFIG, "-1",
                                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5",
                                ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000")),
                        SEND_TIMEOUT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeProducers")
    void refusesAProducerThatCouldLoseReorderOrStall(String flaw, Map<String, Object> config) {
        assertThatIllegalStateException()
                .isThrownBy(() -> KafkaEventPublisher.requireDurableProducer(config, SEND_TIMEOUT));
    }

    static Stream<Arguments> unsafeProducers() {
        return Stream.of(
                Arguments.of("acks left to Kafka's default", without(ProducerConfig.ACKS_CONFIG)),
                Arguments.of("acks=1", with(Map.of(ProducerConfig.ACKS_CONFIG, "1"))),
                Arguments.of("idempotence left to the default", without(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)),
                Arguments.of("idempotence off", with(Map.of(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false"))),
                Arguments.of(
                        "six requests in flight",
                        with(Map.of(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "6"))),
                Arguments.of("max.block.ms left to its 60 s default", without(ProducerConfig.MAX_BLOCK_MS_CONFIG)),
                Arguments.of(
                        "max.block.ms beyond the send timeout",
                        with(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10001"))));
    }

    private static Map<String, Object> with(Map<String, Object> overrides) {
        Map<String, Object> config = new HashMap<>(DURABLE);
        config.putAll(overrides);
        return config;
    }

    private static Map<String, Object> without(String setting) {
        Map<String, Object> config = new HashMap<>(DURABLE);
        config.remove(setting);
        return config;
    }

    private static MessagingProperties sendTimeout(Duration timeout) {
        return new MessagingProperties(
                Publisher.KAFKA,
                new Relay(
                        Duration.ofMillis(250),
                        100,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5)),
                Duration.ofDays(7),
                new Purge(Duration.ofHours(1), 1000),
                new Kafka(timeout));
    }
}
