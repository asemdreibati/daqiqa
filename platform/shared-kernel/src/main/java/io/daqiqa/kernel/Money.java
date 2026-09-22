package io.daqiqa.kernel;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in minor units (fils, halalas, cents) of one currency.
 *
 * <p>Integers, never floating point: {@code 0.1 + 0.2 != 0.3} is not a rounding error you want on a receipt.
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money times(int multiplier) {
        return new Money(Math.multiplyExact(amountMinor, multiplier), currency);
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    /** The amount in major units, e.g. {@code 12.50} for 1250 fils. For display and APIs only. */
    public BigDecimal toMajorUnits() {
        return BigDecimal.valueOf(amountMinor, currency.getDefaultFractionDigits());
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return toMajorUnits().toPlainString() + " " + currency.getCurrencyCode();
    }
}
