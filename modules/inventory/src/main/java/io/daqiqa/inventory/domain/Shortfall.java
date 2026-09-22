package io.daqiqa.inventory.domain;

import io.daqiqa.kernel.Sku;
import java.util.Objects;

/** A basket line a store cannot cover: {@code requested} units were asked for, {@code available} can be sold. */
public record Shortfall(Sku sku, int requested, int available) {

    public Shortfall {
        Objects.requireNonNull(sku, "sku");
        if (available < 0 || available >= requested) {
            throw new IllegalArgumentException(
                    "A shortfall needs 0 <= available < requested, was " + available + " of " + requested);
        }
    }
}
