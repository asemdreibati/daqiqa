package io.daqiqa.messaging.outbox;

import io.daqiqa.contracts.events.IntegrationEvent;
import io.daqiqa.messaging.OutboxWriter;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends integration events to {@code messaging.outbox_event} inside the caller's transaction (ADR-0009).
 *
 * <p>Events are serialised with a dedicated {@link JsonMapper}, not the application's HTTP mapper: the payload is a
 * published contract, and it must not change shape because someone tunes {@code spring.jackson.*} for the web API.
 * Jackson 3 defaults are what the contract relies on (ISO-8601 instants, record components as properties).
 */
@Component
class JdbcOutboxWriter implements OutboxWriter {

    private final OutboxRepository repository;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcOutboxWriter(OutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Refuses to run without a transaction: an event appended in auto-commit mode would be committed even if the
     * state change it describes is later rolled back, which is exactly the dual write the outbox exists to prevent.
     */
    @Override
    public void append(IntegrationEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox events must be appended inside the transaction that changes the "
                    + "state they describe; no transaction is active for " + event.type());
        }
        repository.insert(
                event.eventId(),
                event.type(),
                event.topic(),
                event.partitionKey(),
                json.writeValueAsString(event),
                event.occurredAt(),
                clock.instant());
    }
}
