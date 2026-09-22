package io.daqiqa.messaging;

import io.daqiqa.contracts.events.IntegrationEvent;

/**
 * Appends integration events to the transactional outbox.
 *
 * <p>Must be called inside the transaction that makes the state change the event describes. The event is then
 * committed atomically with that change, and published to Kafka afterwards by the outbox relay. Delivery is
 * at-least-once, so consumers deduplicate on {@link IntegrationEvent#eventId()}.
 *
 * @throws IllegalStateException if there is no active transaction
 */
public interface OutboxWriter {

    void append(IntegrationEvent event);
}
