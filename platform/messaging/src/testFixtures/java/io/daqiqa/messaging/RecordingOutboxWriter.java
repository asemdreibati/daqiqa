package io.daqiqa.messaging;

import io.daqiqa.contracts.events.IntegrationEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that records appended events in memory. Thread-safe. */
public final class RecordingOutboxWriter implements OutboxWriter {

    private final List<IntegrationEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(IntegrationEvent event) {
        events.add(event);
    }

    public List<IntegrationEvent> events() {
        return List.copyOf(events);
    }

    public <T extends IntegrationEvent> List<T> eventsOfType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    public void clear() {
        events.clear();
    }
}
