package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.ReservationId;
import io.daqiqa.kernel.SkuQuantity;
import io.daqiqa.kernel.StoreId;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stock held at one store for one caller reference (usually an order id), and the state machine that decides what
 * happens to it. Immutable: every change of status is a {@link Transition} to a new instance, which the caller
 * persists.
 *
 * @param lines what is held, one line per SKU, kept in SKU order (the order stock rows are locked in)
 * @param endReason why the reservation ended; present exactly when its status no longer holds stock
 */
public record Reservation(
        ReservationId id,
        StoreId storeId,
        String reference,
        List<SkuQuantity> lines,
        ReservationStatus status,
        Instant expiresAt,
        @Nullable EndReason endReason) {

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
        if (status.holdsStock() != (endReason == null)) {
            throw new IllegalArgumentException("A reservation has an end reason exactly when it has ended; " + id
                    + " is " + status + " with reason " + endReason);
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
        return new Reservation(id, storeId, reference, lines, ReservationStatus.HELD, now.plus(holdFor), null);
    }

    /** The stock rows this reservation holds units of. */
    public List<StockKey> stockKeys() {
        return lines.stream().map(line -> new StockKey(storeId, line.sku())).toList();
    }

    /**
     * Whether this is a hold whose time ran out at {@code now}: from {@code expiresAt} on, it can no longer be
     * confirmed and must expire, whether or not the sweeper has got to it yet.
     */
    public boolean isHeldPast(Instant now) {
        return status == ReservationStatus.HELD && !now.isBefore(expiresAt);
    }

    /**
     * Confirming at {@code now}. A held reservation whose time has run out expires instead, even if the sweeper has not
     * run yet: a late confirmation must never succeed.
     */
    public ConfirmOutcome confirm(Instant now) {
        return switch (status) {
            case HELD ->
                isHeldPast(now)
                        ? new ConfirmOutcome.HoldElapsed(moveTo(ReservationStatus.EXPIRED, EndReason.EXPIRED))
                        : new ConfirmOutcome.Confirmed(moveTo(ReservationStatus.CONFIRMED, null));
            case CONFIRMED -> new ConfirmOutcome.AlreadyConfirmed();
            case RELEASED, EXPIRED -> new ConfirmOutcome.NoLongerHeld(status);
        };
    }

    /** Releasing for {@code reason}: held or confirmed stock goes back on sale; an ended reservation stays as it is. */
    public ReleaseOutcome release(EndReason reason) {
        Objects.requireNonNull(reason, "reason");
        return switch (status) {
            case HELD, CONFIRMED -> new ReleaseOutcome.Released(moveTo(ReservationStatus.RELEASED, reason));
            case RELEASED, EXPIRED -> new ReleaseOutcome.AlreadyEnded(status);
        };
    }

    /**
     * Expiring a hold nobody confirmed in time.
     *
     * @throws IllegalStateException unless this is a hold whose time has run out at {@code now}
     */
    public Transition expire(Instant now) {
        if (!isHeldPast(now)) {
            throw new IllegalStateException("Only a held reservation past its expiry can expire; " + id + " is "
                    + status + " until " + expiresAt + " (now " + now + ")");
        }
        return moveTo(ReservationStatus.EXPIRED, EndReason.EXPIRED);
    }

    private Transition moveTo(ReservationStatus next, @Nullable EndReason reason) {
        return new Transition(this, new Reservation(id, storeId, reference, lines, next, expiresAt, reason));
    }
}
