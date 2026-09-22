package io.daqiqa.kernel;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stock keeping unit: identifies a sellable product, for example {@code WATER-500ML-12PK}. */
public record Sku(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,39}$");

    public Sku {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SKU: '" + value + "'");
        }
    }

    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
