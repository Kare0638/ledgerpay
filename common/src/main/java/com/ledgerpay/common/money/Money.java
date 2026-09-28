package com.ledgerpay.common.money;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in integer minor units (pence for GBP): {@code 1050} is £10.50.
 *
 * <p>Amounts are signed because ledger postings are signed (debit positive, credit negative) and a
 * merchant payable balance can go below zero. Use {@link #positive(long, Currency)} where only a
 * positive amount is valid, such as a payment or refund request. Arithmetic never mixes currencies
 * and throws {@link ArithmeticException} on overflow instead of wrapping.
 *
 * <p>See docs/adr/0001-money-as-integer-minor-units.md.
 */
public record Money(long minor, Currency currency) {

  public static final Currency GBP = Currency.getInstance("GBP");

  public Money {
    Objects.requireNonNull(currency, "currency");
  }

  public static Money of(long minor, Currency currency) {
    return new Money(minor, currency);
  }

  /** An amount that must be greater than zero, such as a payment or refund amount. */
  public static Money positive(long minor, Currency currency) {
    if (minor <= 0) {
      throw new IllegalArgumentException("amount must be positive: " + minor);
    }
    return new Money(minor, currency);
  }

  public static Money zero(Currency currency) {
    return new Money(0, currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(minor, other.minor), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(minor, other.minor), currency);
  }

  public Money negate() {
    return new Money(Math.negateExact(minor), currency);
  }

  public boolean isPositive() {
    return minor > 0;
  }

  public boolean isNegative() {
    return minor < 0;
  }

  public boolean isZero() {
    return minor == 0;
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other");
    if (!currency.equals(other.currency)) {
      throw new CurrencyMismatchException(currency, other.currency);
    }
  }
}
