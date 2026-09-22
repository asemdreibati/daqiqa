package io.daqiqa.messaging.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A committed outbox row that has not been published yet, exactly as the relay hands it to an {@link EventPublisher}.
 *
 * @param id the event id ({@code IntegrationEvent.eventId()}); consumers deduplicate on it
 * @param seq position in publication order
 * @param type versioned event type, for example {@code daqiqa.ordering.order-placed.v1}
 * @param topic Kafka topic
 * @param partitionKey Kafka record key: events with the same key keep their order
 * @param payload the event serialised as a JSON object
 * @param occurredAt when the fact happened in the business domain
 * @param attempts failed publication attempts so far
 */
public record PendingEvent(
        UUID id,
        long seq,
        String type,
        String topic,
        String partitionKey,
        String payload,
        Instant occurredAt,
        int attempts) {

    public PendingEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
