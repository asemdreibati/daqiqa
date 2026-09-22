package io.daqiqa.inventory.support;

import io.daqiqa.contracts.events.IntegrationEvent;
import io.daqiqa.messaging.OutboxWriter;
import io.daqiqa.messaging.RecordingOutboxWriter;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The outbox as the real one behaves, for tests: an append outside a transaction fails (the {@link OutboxWriter}
 * contract), and an event is recorded only if its transaction commits, so a rolled-back change leaves no event behind.
 * Committed events are kept in a {@link RecordingOutboxWriter}.
 */
public final class TransactionalRecordingOutbox implements OutboxWriter {

    private final RecordingOutboxWriter committed = new RecordingOutboxWriter();
    private volatile Consumer<IntegrationEvent> probe = event -> {};

    @Override
    public void append(IntegrationEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Events must be appended inside the transaction that changes the state");
        }
        probe.accept(event);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committed.append(event);
            }
        });
    }

    public List<IntegrationEvent> committedEvents() {
        return committed.events();
    }

    public <T extends IntegrationEvent> List<T> committedEventsOfType(Class<T> type) {
        return committed.eventsOfType(type);
    }

    /** Runs {@code probe} inside every append, in the appending transaction: to look at the world at that moment. */
    public void whenAppending(Consumer<IntegrationEvent> probe) {
        this.probe = probe;
    }

    public void clear() {
        committed.clear();
        probe = event -> {};
    }
}
