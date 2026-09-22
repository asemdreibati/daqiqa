package io.daqiqa.messaging.outbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Test double of the publisher port: records every publication in order and fails on demand. Thread-safe. */
final class RecordingPublisher implements EventPublisher {

    private final List<UUID> published = new CopyOnWriteArrayList<>();
    private volatile Predicate<PendingEvent> failing = event -> false;
    private volatile Predicate<PendingEvent> crashing = event -> false;
    private volatile Runnable beforePublish = () -> {};

    @Override
    public void publish(PendingEvent event) {
        beforePublish.run();
        if (crashing.test(event)) {
            throw new SimulatedCrash();
        }
        if (failing.test(event)) {
            throw new EventPublicationException(event, new IllegalStateException("broker unavailable"));
        }
        published.add(event.id());
    }

    /** Events matching the predicate fail with an {@link EventPublicationException} until {@link #heal()}. */
    void failWhen(Predicate<PendingEvent> predicate) {
        failing = predicate;
    }

    /** Events matching the predicate kill the relay's thread with an {@link Error}, as a crashed process would. */
    void crashWhen(Predicate<PendingEvent> predicate) {
        crashing = predicate;
    }

    /** Runs before every publication, successful or not: how a test makes the broker slow. */
    void beforeEachPublish(Runnable action) {
        beforePublish = action;
    }

    void heal() {
        failing = event -> false;
        crashing = event -> false;
    }

    List<UUID> published() {
        return List.copyOf(published);
    }

    static final class SimulatedCrash extends Error {

        SimulatedCrash() {
            super("simulated crash between publishing and committing");
        }
    }
}
