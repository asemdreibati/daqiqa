package io.daqiqa.inventory.application;

import io.daqiqa.inventory.api.ReleaseReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The numbers an operator asks about: how reservations turn out, why stock is released, how many holds expire. */
@Component
class InventoryMetrics {

    static final String RESERVATIONS = "daqiqa.inventory.reservations";
    static final String RELEASES = "daqiqa.inventory.releases";
    static final String EXPIRATIONS = "daqiqa.inventory.expirations";
    static final String RESERVE = "daqiqa.inventory.reserve";

    enum ReserveOutcome {
        /** Stock was held. */
        RESERVED,
        /** Some line could not be covered; nothing was held. */
        REJECTED,
        /** The reference already had an active reservation, which was returned. */
        REPLAYED
    }

    enum ExpiryTrigger {
        /** The scheduled sweep found the hold elapsed. */
        SWEEPER,
        /** A confirmation arrived after the hold had elapsed (payment slower than the hold, typically). */
        LATE_CONFIRM,
        /** A retried reservation found its reference's hold elapsed before the sweeper did. */
        REPLAY
    }

    private final Map<ReserveOutcome, Counter> reservations;
    private final Map<ReleaseReason, Counter> releases;
    private final Map<ExpiryTrigger, Counter> expirations;
    private final Timer reserve;

    InventoryMetrics(MeterRegistry registry) {
        reservations = counters(
                ReserveOutcome.class,
                outcome -> Counter.builder(RESERVATIONS)
                        .description("Reservation attempts, by outcome")
                        .tag("outcome", tagValue(outcome))
                        .register(registry));
        releases = counters(
                ReleaseReason.class,
                reason -> Counter.builder(RELEASES)
                        .description("Reservations released on request, by reason")
                        .tag("reason", tagValue(reason))
                        .register(registry));
        expirations = counters(
                ExpiryTrigger.class,
                trigger -> Counter.builder(EXPIRATIONS)
                        .description("Holds that expired before being confirmed, by what noticed it")
                        .tag("trigger", tagValue(trigger))
                        .register(registry));
        reserve = Timer.builder(RESERVE)
                .description("Time to decide and apply a reservation, including waiting for row locks")
                .publishPercentileHistogram()
                .register(registry);
    }

    <T> T timeReserve(Supplier<T> reservation) {
        return reserve.record(reservation);
    }

    void reservation(ReserveOutcome outcome) {
        reservations.get(outcome).increment();
    }

    void released(ReleaseReason reason) {
        releases.get(reason).increment();
    }

    void expired(int count, ExpiryTrigger trigger) {
        expirations.get(trigger).increment(count);
    }

    private static <E extends Enum<E>> Map<E, Counter> counters(Class<E> keys, Function<E, Counter> counter) {
        return Arrays.stream(keys.getEnumConstants())
                .collect(Collectors.toUnmodifiableMap(Function.identity(), counter));
    }

    private static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
