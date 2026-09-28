package com.ledgerpay.common.money;

import static com.ledgerpay.common.money.Money.GBP;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/** Checks the long-arithmetic fee against a BigDecimal reference, used only here in tests. */
class FeesPropertyTest {

  @Property(tries = 10_000)
  void matchesBigDecimalHalfUp(
      @ForAll @LongRange(min = 0, max = 100_000_000) long amount,
      @ForAll @IntRange(min = 0, max = 10_000) int bps) {
    long expected =
        BigDecimal.valueOf(amount)
            .multiply(BigDecimal.valueOf(bps))
            .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP)
            .longValueExact();

    assertThat(Fees.calculate(Money.of(amount, GBP), bps).minor()).isEqualTo(expected);
  }

  @Property(tries = 10_000)
  void feeNeverExceedsTheAmount(
      @ForAll @LongRange(min = 0, max = 100_000_000) long amount,
      @ForAll @IntRange(min = 0, max = 10_000) int bps) {
    long fee = Fees.calculate(Money.of(amount, GBP), bps).minor();

    assertThat(fee).isBetween(0L, amount);
  }

  @Property(tries = 10_000)
  void feeGrowsWithTheAmount(
      @ForAll @LongRange(min = 0, max = 99_999_999) long amount,
      @ForAll @IntRange(min = 0, max = 10_000) int bps) {
    long fee = Fees.calculate(Money.of(amount, GBP), bps).minor();
    long nextFee = Fees.calculate(Money.of(amount + 1, GBP), bps).minor();

    assertThat(nextFee).isGreaterThanOrEqualTo(fee);
  }
}
