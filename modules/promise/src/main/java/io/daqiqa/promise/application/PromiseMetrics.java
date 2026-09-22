package io.daqiqa.promise.application;

import io.daqiqa.promise.api.DeliveryTier;
import io.daqiqa.promise.domain.UnavailableReason;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Promise metrics:
 *
 * <ul>
 *   <li>{@code daqiqa.promise.requests}: timer with a percentile histogram and a 25 ms bucket (the p99 target), tagged
 *       {@code status} (AVAILABLE, UNAVAILABLE), {@code tier} and {@code reason} ({@code none} when not applicable);
 *   <li>{@code daqiqa.promise.eta}: distribution of promised ETAs, in minutes.
 * </ul>
 *
 * Every tag combination is registered up front, so dashboards and alerts see zeros rather than missing series.
 * Requests that fail with an exception are counted by the HTTP server metrics, not here.
 */
@Component
class PromiseMetrics {

    static final String NONE = "none";

    private final MeterRegistry registry;
    private final Map<DeliveryTier, Timer> available = new EnumMap<>(DeliveryTier.class);
    private final Map<UnavailableReason, Timer> unavailable = new EnumMap<>(UnavailableReason.class);
    private final DistributionSummary eta;

    PromiseMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (DeliveryTier tier : DeliveryTier.values()) {
            available.put(tier, timer("AVAILABLE", tier.name(), NONE));
        }
        for (UnavailableReason reason : UnavailableReason.values()) {
            unavailable.put(reason, timer("UNAVAILABLE", NONE, reason.name()));
        }
        this.eta = DistributionSummary.builder("daqiqa.promise.eta")
                .description("Promised delivery time")
                .baseUnit("minutes")
                .publishPercentileHistogram()
                .minimumExpectedValue(1.0)
                .maximumExpectedValue((double) DeliveryTier.EXTENDED_60.maxMinutes())
                .register(registry);
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    void record(Timer.Sample sample, PromiseResult result) {
        switch (result) {
            case PromiseResult.Available promise -> {
                sample.stop(Objects.requireNonNull(
                        available.get(promise.offer().quote().tier())));
                eta.record(promise.offer().quote().etaMinutes());
            }
            case PromiseResult.Unavailable none -> sample.stop(Objects.requireNonNull(unavailable.get(none.reason())));
        }
    }

    private Timer timer(String status, String tier, String reason) {
        return Timer.builder("daqiqa.promise.requests")
                .description("Promise requests, from basket to signed answer")
                .tag("status", status)
                .tag("tier", tier)
                .tag("reason", reason)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(25))
                .register(registry);
    }
}
