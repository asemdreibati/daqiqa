package io.daqiqa.messaging.outbox;

/**
 * Outbound port of the relay: hands one committed event to the outside world.
 *
 * <p>Implementations must be synchronous: {@link #publish(PendingEvent)} returns only once the event is durably
 * accepted (for Kafka: acknowledged by all in-sync replicas). The relay marks an event published only after that, so a
 * crash in between re-publishes it (at-least-once) and never loses it.
 */
public interface EventPublisher {

    /**
     * Publishes one event and waits for it to be accepted.
     *
     * @throws EventPublicationException if the event could not be published; the relay records the attempt and retries
     *     it (and everything after it) in a later round
     */
    void publish(PendingEvent event);
}
