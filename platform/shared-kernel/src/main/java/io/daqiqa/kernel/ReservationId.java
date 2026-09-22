package io.daqiqa.kernel;

import java.util.Objects;
import java.util.UUID;

/** Identifier of an inventory reservation (stock held for an order). Backed by a time-ordered UUIDv7 (see {@link Ids}). */
public record ReservationId(UUID value) {

    public ReservationId {
        Objects.requireNonNull(value, "value");
    }

    public static ReservationId newId() {
        return new ReservationId(Ids.newTimeOrderedId());
    }

    public static ReservationId of(UUID value) {
        return new ReservationId(value);
    }

    public static ReservationId of(String value) {
        return new ReservationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
