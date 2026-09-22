package io.daqiqa.messaging.config;

import io.daqiqa.messaging.outbox.MessagingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@code daqiqa.messaging.*}. The outbox components are found by component scanning; the application
 * provides the {@link java.time.Clock}, the scheduler and, for the Kafka publisher, the {@code spring.kafka.*}
 * producer (see {@code docs/design/ordering.md} for the settings it must use).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
class MessagingConfiguration {}
