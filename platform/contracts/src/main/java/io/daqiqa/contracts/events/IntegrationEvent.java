package io.daqiqa.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** Common envelope of every integration event. */
public interface IntegrationEvent {

    /** Unique id of this occurrence (UUIDv7). Consumers deduplicate on it. */
    UUID eventId();

    /** When the fact happened in the business domain. */
    Instant occurredAt();

    /** Stable, versioned type name, for example {@code daqiqa.ordering.order-placed.v1}. */
    String type();

    /** Kafka topic the event is published to. */
    String topic();

    /**
     * Partitioning key. Events with the same key are delivered in order. For store-scoped facts this is the store
     * id, which gives every store a single, ordered stream (see ADR-0009).
     */
    String partitionKey();
}
