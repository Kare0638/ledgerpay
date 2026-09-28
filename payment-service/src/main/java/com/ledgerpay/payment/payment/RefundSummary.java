package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;

/**
 * How much of a captured payment has been refunded. Derived from amounts and never stored in place
 * of the payment status. Reserved (in-flight) refunds do not count: only confirmed ones do.
 */
public enum RefundSummary {
  NONE,
  PARTIAL,
  FULL;

  public static RefundSummary of(Money captured, Money refunded) {
    Money remaining = captured.minus(refunded);
    if (refunded.isNegative() || remaining.isNegative()) {
      throw new IllegalArgumentException(
          "refunded must be between 0 and captured: captured="
              + captured.minor()
              + ", refunded="
              + refunded.minor());
    }
    if (refunded.isZero()) {
      return NONE;
    }
    return remaining.isZero() ? FULL : PARTIAL;
  }
}
