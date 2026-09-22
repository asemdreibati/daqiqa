package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Stock held at one store for one caller reference (usually an order id), and the state machine that decides what
 * happens to it. Immutable: every transition returns the next state; the caller persists it.
 *
 * @param lines what is held, one line per SKU, kept in SKU order (the order stock rows are locked in)
 */
public record Reservation(
        ReservationId id,
        StoreId storeId,
        String reference,
        List<SkuQuantity> lines,
        ReservationStatus status,
        Instant expiresAt) {

    public static final int MAX_REFERENCE_LENGTH = 64;

    private static final Comparator<SkuQuantity> SKU_ORDER =
            Comparator.comparing(line -> line.sku().value());

    public Reservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("A reference must have 1 to " + MAX_REFERENCE_LENGTH + " characters");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A reservation holds at least one line");
        }
        lines = lines.stream().sorted(SKU_ORDER).toList();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).sku().equals(lines.get(i - 1).sku())) {
                throw new IllegalArgumentException(
                        "A reservation holds each SKU once, got " + lines.get(i).sku() + " twice");
            }
        }
    }

    /** A new hold that lasts {@code holdFor} from {@code now}. */
    public static Reservation hold(
            ReservationId id,
            StoreId storeId,
            String reference,
            List<SkuQuantity> lines,
            Instant now,
            Duration holdFor) {
        if (holdFor.isNegative() || holdFor.isZero()) {
            throw new IllegalArgumentException("A hold must last a positive time, was " + holdFor);
        }
        return new Reservation(id, storeId, reference, lines, ReservationStatus.HELD, now.plus(holdFor));
    }

    /** Whether the hold has run out at {@code now}. From {@code expiresAt} on, it can no longer be confirmed. */
    public boolean isElapsedAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Confirming at {@code now}. A held reservation whose time has run out expires instead, even if the sweeper has not
     * run yet: a late confirmation must never succeed.
     */
    public ConfirmOutcome confirm(Instant now) {
        return switch (status) {
            case HELD ->
                isElapsedAt(now)
                        ? new ConfirmOutcome.HoldElapsed(withStatus(ReservationStatus.EXPIRED))
                        : new ConfirmOutcome.Confirmed(withStatus(ReservationStatus.CONFIRMED));
            case CONFIRMED -> new ConfirmOutcome.AlreadyConfirmed();
            case RELEASED, EXPIRED -> new ConfirmOutcome.NoLongerHeld(status);
        };
    }

    /** Releasing: held or confirmed stock goes back on sale; an ended reservation stays as it is. */
    public ReleaseOutcome release() {
        return switch (status) {
            case HELD, CONFIRMED -> new ReleaseOutcome.Released(withStatus(ReservationStatus.RELEASED));
            case RELEASED, EXPIRED -> new ReleaseOutcome.AlreadyEnded(status);
        };
    }

    /**
     * The sweeper's transition: a hold nobody confirmed in time expires.
     *
     * @throws IllegalStateException if the reservation is not held or its time has not run out
     */
    public Reservation expire(Instant now) {
        if (status != ReservationStatus.HELD || !isElapsedAt(now)) {
            throw new IllegalStateException("Only a held reservation past its expiry can expire; " + id + " is "
                    + status + " until " + expiresAt + " (now " + now + ")");
        }
        return withStatus(ReservationStatus.EXPIRED);
    }

    private Reservation withStatus(ReservationStatus next) {
        return new Reservation(id, storeId, reference, lines, next, expiresAt);
    }
}
