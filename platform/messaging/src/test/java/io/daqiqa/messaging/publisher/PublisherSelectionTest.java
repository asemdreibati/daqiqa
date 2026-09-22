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

/** {@code daqiqa.messaging.publisher} picks exactly one publisher, and the defaults need no configuration at all. */
class PublisherSelectionTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(Properties.class, LoggingEventPublisher.class, KafkaEventPublisher.class);

    @Test
    void publishesToTheLogByDefault() {
        contexts.run(context -> assertThat(context)
                .hasSingleBean(EventPublisher.class)
                .getBean(EventPublisher.class)
                .isInstanceOf(LoggingEventPublisher.class));
    }

    @Test
    void publishesToKafkaWhenConfigured() {
        contexts.withPropertyValues("daqiqa.messaging.publisher=kafka")
                .run(context -> assertThat(context)
                        .hasSingleBean(EventPublisher.class)
                        .getBean(EventPublisher.class)
                        .isInstanceOf(KafkaEventPublisher.class));
    }

    @Test
    void refusesToStartWithAKafkaProducerThatCouldLoseEvents() {
        contexts.withPropertyValues("daqiqa.messaging.publisher=kafka", "spring.kafka.producer.acks=1")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("acks=all"));
    }

    @Test
    void refusesToStartWithAnUnknownPublisher() {
        contexts.withPropertyValues("daqiqa.messaging.publisher=carrier-pigeon")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void everySettingHasADefault() {
        contexts.run(context -> assertThat(context.getBean(MessagingProperties.class))
                .isEqualTo(new MessagingProperties(
                        Publisher.LOGGING,
                        new Relay(Duration.ofMillis(250), 100, Duration.ofSeconds(1), Duration.ofMinutes(5)),
                        Duration.ofDays(7),
                        new Purge(Duration.ofHours(1), 1000),
                        new Kafka(Duration.ofSeconds(10)))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MessagingProperties.class)
    static class Properties {}
}
