package io.daqiqa.kernel;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier of a dark store, for example {@code DXB-MARINA}.
 *
 * <p>Human-readable on purpose: store ids show up in logs, dashboards, Kafka keys and simulation output, and an
 * operator should recognise them at a glance. Format: a three-letter city code, then upper-case segments.
 */
public record StoreId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{3}(-[A-Z0-9]+)+$");

    public StoreId {
        Objects.requireNonNull(value, "value");
        if (value.length() > 40 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid store id: '" + value + "'");
        }
    }

    public static StoreId of(String value) {
        return new StoreId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
