package io.daqiqa.inventory.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests move by hand, so expiry is tested in milliseconds and without sleeping. Thread-safe. */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-09-22T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The inventory module works in UTC instants only");
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    public void reset() {
        now.set(START);
    }
}
