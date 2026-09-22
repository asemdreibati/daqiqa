package io.daqiqa.ordering.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * {@code daqiqa.ordering.checkouts} (counter) and {@code daqiqa.ordering.checkout} (timer), both tagged with the
 * {@code outcome}: how many checkouts confirm, sell out, get declined or replay, and how long each kind takes.
 */
@Component
class CheckoutMetrics {

    private final MeterRegistry registry;

    CheckoutMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    Timer.Sample start() {
        return Timer.start(registry);
    }

    void record(Timer.Sample sample, CheckoutOutcome outcome) {
        sample.stop(Timer.builder("daqiqa.ordering.checkout")
                .description("Time to answer a checkout request, payment included")
                .tag("outcome", outcome.tag())
                .register(registry));
        Counter.builder("daqiqa.ordering.checkouts")
                .description("Checkout requests by outcome")
                .tag("outcome", outcome.tag())
                .register(registry)
                .increment();
    }
}
