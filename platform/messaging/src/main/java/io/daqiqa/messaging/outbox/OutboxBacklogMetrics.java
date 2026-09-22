package io.daqiqa.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code daqiqa.outbox.backlog}: committed events not yet published. A growing backlog means the relay is
 * stuck (broker down, poison event) or cannot keep up. The count is read when the registry is scraped, from the
 * partial index on unpublished rows, so it is always current and costs nothing between scrapes.
 */
@Component
class OutboxBacklogMetrics implements MeterBinder {

    private final OutboxRepository repository;

    OutboxBacklogMetrics(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("daqiqa.outbox.backlog", repository, OutboxRepository::countUnpublished)
                .description("Committed integration events waiting to be published")
                .baseUnit("events")
                .register(registry);
    }
}
