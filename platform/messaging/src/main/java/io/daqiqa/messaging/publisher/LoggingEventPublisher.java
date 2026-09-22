package io.daqiqa.messaging.publisher;

import io.daqiqa.messaging.outbox.EventPublisher;
import io.daqiqa.messaging.outbox.PendingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to the log, for local runs without a broker. An explicit opt-in
 * ({@code daqiqa.messaging.publisher=logging}), never the default: the relay marks what it publishes as published, so
 * a deployment that fell back to this publisher by missing configuration would silently drop every event.
 *
 * <p>Logs the envelope only, never the payload: payloads can carry personal data (an order's delivery location).
 */
@Component
@ConditionalOnProperty(name = "daqiqa.messaging.publisher", havingValue = "logging")
class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(PendingEvent event) {
        log.info("Published {} {} to {} with key {}", event.type(), event.id(), event.topic(), event.partitionKey());
    }
}
