package io.daqiqa.promise.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Time to pick and pack a basket: {@code base + perUnit × units}, capped at {@code max} because a very large basket is
 * split between several pickers.
 */
public record PickTimeModel(Duration base, Duration perUnit, Duration max) {

    public PickTimeModel {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(perUnit, "perUnit");
        Objects.requireNonNull(max, "max");
        if (base.isNegative() || perUnit.isNegative()) {
            throw new IllegalArgumentException("Pick times cannot be negative: base " + base + ", per unit " + perUnit);
        }
        if (max.compareTo(base) < 0) {
            throw new IllegalArgumentException("The pick time cap " + max + " is below the base time " + base);
        }
    }

    public Duration pickTime(int units) {
        if (units < 1) {
            throw new IllegalArgumentException("A basket has at least one unit, got " + units);
        }
        Duration time = base.plus(perUnit.multipliedBy(units));
        return time.compareTo(max) > 0 ? max : time;
    }
}
