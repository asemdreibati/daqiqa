package io.daqiqa.promise.domain;

import java.time.Duration;

/**
 * Where the minutes of a promise go, in whole seconds.
 *
 * <p>Rounding: each part is rounded <em>up</em> to the second, and the ETA is the sum rounded up to the minute (and at
 * least one minute). Rounding therefore never makes a promise optimistic, and the parts shown to the customer always
 * add up to the ETA they are shown.
 */
public record EtaBreakdown(
        int queueSeconds, int pickSeconds, int riderWaitSeconds, int travelSeconds, int bufferSeconds) {

    public EtaBreakdown {
        if (queueSeconds < 0 || pickSeconds < 0 || riderWaitSeconds < 0 || travelSeconds < 0 || bufferSeconds < 0) {
            throw new IllegalArgumentException("ETA parts cannot be negative");
        }
    }

    public static EtaBreakdown of(
            Duration pickerQueue, Duration pick, Duration riderWait, Duration travel, Duration buffer) {
        return new EtaBreakdown(
                secondsRoundedUp(pickerQueue),
                secondsRoundedUp(pick),
                secondsRoundedUp(riderWait),
                secondsRoundedUp(travel),
                secondsRoundedUp(buffer));
    }

    public int totalSeconds() {
        return Math.addExact(
                Math.addExact(Math.addExact(queueSeconds, pickSeconds), Math.addExact(riderWaitSeconds, travelSeconds)),
                bufferSeconds);
    }

    /** The total rounded up to whole minutes; never zero, because a promise is never "now". */
    public int etaMinutes() {
        return Math.max(1, Math.ceilDiv(totalSeconds(), 60));
    }

    private static int secondsRoundedUp(Duration duration) {
        long seconds = duration.getNano() == 0 ? duration.getSeconds() : duration.getSeconds() + 1;
        return Math.toIntExact(seconds);
    }
}
