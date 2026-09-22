package io.daqiqa.ordering.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that tests set and advance by hand (ADR-0011), so time-dependent behaviour is tested without sleeping. */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-09-22T12:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    public void set(Instant instant) {
        now.set(instant);
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

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
        throw new UnsupportedOperationException("Daqiqa works in UTC");
    }
}
