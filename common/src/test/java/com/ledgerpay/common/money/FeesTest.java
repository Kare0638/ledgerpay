package com.ledgerpay.common.money;

import static com.ledgerpay.common.money.Money.GBP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FeesTest {

  @ParameterizedTest(name = "{0}p at {1} bps -> {2}p")
  @CsvSource({
    // amount, bps, fee
    "1,         100,   0", // 0.01p rounds down
    "49,        100,   0", // 0.49p rounds down
    "50,        100,   1", // exactly half rounds up
    "149,       100,   1", // 1.49p rounds down
    "150,       100,   2", // 1.5p rounds up
    "151,       100,   2",
    "10000,     100,   100", // AT-01: £100 at 1% is £1
    "100000000, 100,   1000000", // maximum payment amount
    "100000000, 10000, 100000000", // 100% fee takes the whole amount
    "100000000, 0,     0",
    "1,         0,     0",
    "1,         10000, 1",
    "1,         5000,  1", // 0.5p rounds up
    "1,         4999,  0",
    "3,         5000,  2", // 1.5p rounds up
    "0,         100,   0",
  })
  void roundsHalfUpToThePenny(long amount, int bps, long expectedFee) {
    assertThat(Fees.calculate(Money.of(amount, GBP), bps)).isEqualTo(Money.of(expectedFee, GBP));
  }

  @Test
  void keepsTheAmountsCurrency() {
    Currency eur = Currency.getInstance("EUR");

    assertThat(Fees.calculate(Money.of(10000, eur), 100).currency()).isEqualTo(eur);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 10001, Integer.MIN_VALUE, Integer.MAX_VALUE})
  void rejectsBpsOutsideZeroToTenThousand(int bps) {
    assertThatThrownBy(() -> Fees.calculate(Money.of(100, GBP), bps))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("feeBps");
  }

  @Test
  void rejectsNegativeAmounts() {
    assertThatThrownBy(() -> Fees.calculate(Money.of(-1, GBP), 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void overflowThrowsInsteadOfWrapping() {
    assertThatThrownBy(() -> Fees.calculate(Money.of(Long.MAX_VALUE, GBP), 100))
        .isInstanceOf(ArithmeticException.class);
  }
}
