package io.daqiqa.kernel;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a customer order. Backed by a time-ordered UUIDv7 (see {@link Ids}). */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "value");
    }

    public static OrderId newId() {
        return new OrderId(Ids.newTimeOrderedId());
    }

    public static OrderId of(UUID value) {
        return new OrderId(value);
    }

    public static OrderId of(String value) {
        return new OrderId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
