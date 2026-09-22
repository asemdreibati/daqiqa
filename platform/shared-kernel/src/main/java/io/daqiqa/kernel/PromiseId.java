package io.daqiqa.kernel;

import java.util.Objects;
import java.util.UUID;

/** Identifier of a delivery promise shown to a customer. Backed by a time-ordered UUIDv7 (see {@link Ids}). */
public record PromiseId(UUID value) {

    public PromiseId {
        Objects.requireNonNull(value, "value");
    }

    public static PromiseId newId() {
        return new PromiseId(Ids.newTimeOrderedId());
    }

    public static PromiseId of(UUID value) {
        return new PromiseId(value);
    }

    public static PromiseId of(String value) {
        return new PromiseId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
