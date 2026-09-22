package io.daqiqa.kernel;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a customer, owned by an upstream identity system. Backed by a time-ordered UUIDv7 (see {@link Ids}). */
public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "value");
    }

    public static CustomerId newId() {
        return new CustomerId(Ids.newTimeOrderedId());
    }

    public static CustomerId of(UUID value) {
        return new CustomerId(value);
    }

    public static CustomerId of(String value) {
        return new CustomerId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
