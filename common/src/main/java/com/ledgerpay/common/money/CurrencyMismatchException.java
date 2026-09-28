package com.ledgerpay.common.money;

import java.util.Currency;

/** Thrown when an operation would combine amounts in different currencies. */
public class CurrencyMismatchException extends IllegalArgumentException {

  public CurrencyMismatchException(Currency expected, Currency actual) {
    super("currency mismatch: expected " + expected + " but was " + actual);
  }
}
