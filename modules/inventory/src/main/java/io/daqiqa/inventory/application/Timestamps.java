package io.daqiqa.inventory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Times at the precision PostgreSQL stores (microseconds). Everything the module persists and returns goes through
 * here, so what a call returns equals what a later read returns: a replayed reservation reports exactly the
 * {@code expiresAt} the first call reported.
 */
final class Timestamps {

    private Timestamps() {}

    static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static Duration storable(Duration duration) {
        return duration.truncatedTo(ChronoUnit.MICROS);
    }
}
