package io.daqiqa.messaging.outbox;

/** An event could not be published (broker unreachable, not acknowledged in time, record rejected). */
public final class EventPublicationException extends RuntimeException {

    public EventPublicationException(PendingEvent event, Throwable cause) {
        super(
                "Could not publish event " + event.id() + " (" + event.type() + ") to " + event.topic() + ": " + cause,
                cause);
    }
}
