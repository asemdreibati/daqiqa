package io.daqiqa.inventory.application;

import io.daqiqa.inventory.domain.ReservationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * How far behind expiry is: the age of the longest-overdue hold. It is read from the database at each scrape, not
 * remembered by the sweeper, so it keeps rising when expiry stalls for any reason: a poison hold, or a sweeper that
 * stopped running. Alert when it exceeds a few sweep intervals.
 */
@Component
class ExpiryBacklogMetrics implements MeterBinder {

    static final String OLDEST_OVERDUE = "daqiqa.inventory.expiry.oldest.overdue";

    private final ReservationRepository reservations;
    private final Clock clock;

    ExpiryBacklogMetrics(ReservationRepository reservations, Clock clock) {
        this.reservations = reservations;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(OLDEST_OVERDUE, this, ExpiryBacklogMetrics::oldestOverdueSeconds)
                .description("How long ago the longest-overdue hold should have expired (0 when none is overdue)")
                .baseUnit("seconds")
                .register(registry);
    }

    private double oldestOverdueSeconds() {
        Instant now = Timestamps.now(clock);
        return reservations
                .oldestElapsedHoldExpiry(now)
                .map(expiry -> Duration.between(expiry, now).toMillis() / 1_000.0)
                .orElse(0.0);
    }
}
