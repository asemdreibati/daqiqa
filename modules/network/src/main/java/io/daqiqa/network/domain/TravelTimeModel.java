package io.daqiqa.network.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Baseline rider travel time, version 1: {@code straight-line distance × roadFactor / speed + handoff}.
 *
 * <p>The road factor turns a straight line into a road distance (streets are not straight; 1.3 is typical for a
 * dense grid); the handoff covers parking, finding the entrance and handing the bag over. Traffic by time of day is a
 * later refinement behind the same method.
 *
 * @param roadFactor road distance per metre of straight line, at least 1
 * @param speedMetersPerSecond average riding speed (7 m/s ≈ 25 km/h for a scooter in city traffic)
 * @param handoff fixed time at the destination
 */
public record TravelTimeModel(double roadFactor, double speedMetersPerSecond, Duration handoff) {

    public TravelTimeModel {
        Objects.requireNonNull(handoff, "handoff");
        // Written as negated range checks so that NaN and infinity are rejected too.
        if (!(roadFactor >= 1.0 && Double.isFinite(roadFactor))) {
            throw new IllegalArgumentException("Road factor must be a finite number of at least 1, was " + roadFactor);
        }
        if (!(speedMetersPerSecond > 0.0 && Double.isFinite(speedMetersPerSecond))) {
            throw new IllegalArgumentException("Speed must be a finite positive number, was " + speedMetersPerSecond);
        }
        if (handoff.isNegative()) {
            throw new IllegalArgumentException("Handoff cannot be negative, was " + handoff);
        }
    }

    /**
     * Time to ride {@code straightLineMeters} and hand the order over. The riding part is rounded up to whole seconds:
     * the estimate is shown to people, and rounding must never make it optimistic.
     */
    public Duration travelTime(double straightLineMeters) {
        if (!(straightLineMeters >= 0.0 && Double.isFinite(straightLineMeters))) {
            throw new IllegalArgumentException(
                    "Distance must be a finite non-negative number, was " + straightLineMeters);
        }
        double ridingSeconds = straightLineMeters * roadFactor / speedMetersPerSecond;
        return Duration.ofSeconds((long) Math.ceil(ridingSeconds)).plus(handoff);
    }
}
