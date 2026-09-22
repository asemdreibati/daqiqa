package io.daqiqa.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import io.daqiqa.messaging.outbox.EventPublisher;
import io.daqiqa.messaging.outbox.MessagingProperties;
import io.daqiqa.messaging.outbox.MessagingProperties.Kafka;
import io.daqiqa.messaging.outbox.MessagingProperties.Publisher;
import io.daqiqa.messaging.outbox.MessagingProperties.Purge;
import io.daqiqa.messaging.outbox.MessagingProperties.Relay;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * {@code daqiqa.messaging.publisher} picks exactly one publisher. Kafka is the default and needs an explicitly durable
 * producer; the log is an explicit opt-in, so missing configuration can never make events vanish unnoticed.
 */
class PublisherSelectionTest {

    private static final String[] DURABLE_PRODUCER = {
        "spring.kafka.producer.acks=all",
        "spring.kafka.producer.properties.enable.idempotence=true",
        "spring.kafka.producer.properties.max.block.ms=5000"
    };

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(Properties.class, LoggingEventPublisher.class, KafkaEventPublisher.class);

    @Test
    void publishesToKafkaByDefault() {
        contexts.withPropertyValues(DURABLE_PRODUCER)
                .run(context -> assertThat(context)
                        .hasSingleBean(EventPublisher.class)
                        .getBean(EventPublisher.class)
                        .isInstanceOf(KafkaEventPublisher.class));
    }

    @Test
    void refusesToStartWithoutAnExplicitlyDurableProducerAndSaysWhatToSet() {
        contexts.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContainingAll(
                        "acks=all", "enable.idempotence=true", "max.block.ms", "daqiqa.messaging.publisher=logging"));
    }

    @Test
    void publishesToTheLogOnlyWhenAskedTo() {
        contexts.withPropertyValues("daqiqa.messaging.publisher=logging")
                .run(context -> assertThat(context)
                        .hasSingleBean(EventPublisher.class)
                        .getBean(EventPublisher.class)
                        .isInstanceOf(LoggingEventPublisher.class));
    }

    @Test
    void refusesToStartWithAnUnknownPublisher() {
        contexts.withPropertyValues("daqiqa.messaging.publisher=carrier-pigeon")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void everySettingHasADefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(Properties.class)
                .run(context -> assertThat(context.getBean(MessagingProperties.class))
                        .isEqualTo(new MessagingProperties(
                                Publisher.KAFKA,
                                new Relay(
                                        Duration.ofMillis(250),
                                        100,
                                        Duration.ofSeconds(2),
                                        Duration.ofSeconds(1),
                                        Duration.ofMinutes(5)),
                                Duration.ofDays(7),
                                new Purge(Duration.ofHours(1), 1000),
                                new Kafka(Duration.ofSeconds(10)))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MessagingProperties.class)
    static class Properties {}
}
