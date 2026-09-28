package com.ledgerpay.common.money;

import java.util.Objects;

/**
 * Platform fee charged to the merchant on capture.
 *
 * <p>{@code fee = amount × feeBps / 10000}, rounded HALF_UP to the minor unit, computed entirely in
 * {@code long} arithmetic. For a non-negative amount, adding half the divisor before the (flooring)
 * integer division is exactly HALF_UP: 149p at 100 bps is 1.49p → 1p, 150p is 1.5p → 2p.
 */
public final class Fees {

  public static final int BPS_DENOMINATOR = 10_000;

  private Fees() {}

  public static Money calculate(Money amount, int feeBps) {
    Objects.requireNonNull(amount, "amount");
    if (amount.isNegative()) {
      throw new IllegalArgumentException("amount must not be negative: " + amount.minor());
    }
    if (feeBps < 0 || feeBps > BPS_DENOMINATOR) {
      throw new IllegalArgumentException("feeBps must be between 0 and 10000: " + feeBps);
    }
    long scaled = Math.multiplyExact(amount.minor(), (long) feeBps);
    long fee = Math.addExact(scaled, BPS_DENOMINATOR / 2) / BPS_DENOMINATOR;
    return Money.of(fee, amount.currency());
  }
}
