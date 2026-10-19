package io.github.benjaminursu.bankimport.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact amount plus its currency. Never a double: 0.1 + 0.2 is not 0.3 in floating point.
 * <p>
 * Equality uses {@code compareTo}, so 3.5 EUR equals 3.50 EUR. (BigDecimal.equals would say
 * they differ, because it also compares the scale.)
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public int signum() {
        return amount.signum();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money other
                && amount.compareTo(other.amount) == 0
                && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        // stripTrailingZeros so that 3.5 and 3.50 (equal above) also get the same hash code.
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
