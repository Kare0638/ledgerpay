package com.ledgerpay.common.money;

import static com.ledgerpay.common.money.Money.GBP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

  static final Currency EUR = Currency.getInstance("EUR");

  @Test
  void isAValueType() {
    assertThat(Money.of(1050, GBP)).isEqualTo(Money.of(1050, GBP));
    assertThat(Money.of(1050, GBP)).isNotEqualTo(Money.of(1050, EUR));
    assertThat(Money.of(1050, GBP)).isNotEqualTo(Money.of(1051, GBP));
  }

  @Test
  void requiresACurrency() {
    assertThatThrownBy(() -> Money.of(100, null)).isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE})
  void positiveRejectsZeroAndNegativeAmounts(long minor) {
    assertThatThrownBy(() -> Money.positive(minor, GBP))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 100_000_000, Long.MAX_VALUE})
  void positiveAcceptsPositiveAmounts(long minor) {
    assertThat(Money.positive(minor, GBP).minor()).isEqualTo(minor);
  }

  @Test
  void ledgerAmountsMayBeNegative() {
    Money payable = Money.of(-9900, GBP);

    assertThat(payable.isNegative()).isTrue();
    assertThat(payable.negate()).isEqualTo(Money.of(9900, GBP));
  }

  @Test
  void addsAndSubtractsInTheSameCurrency() {
    Money captured = Money.of(10000, GBP);
    Money refunded = Money.of(3000, GBP);

    assertThat(captured.minus(refunded)).isEqualTo(Money.of(7000, GBP));
    assertThat(refunded.plus(refunded)).isEqualTo(Money.of(6000, GBP));
    assertThat(refunded.minus(captured)).isEqualTo(Money.of(-7000, GBP));
  }

  @Test
  void rejectsMixedCurrencies() {
    Money pounds = Money.of(100, GBP);
    Money euros = Money.of(100, EUR);

    assertThatThrownBy(() -> pounds.plus(euros))
        .isInstanceOf(CurrencyMismatchException.class)
        .hasMessage("currency mismatch: expected GBP but was EUR");
    assertThatThrownBy(() -> pounds.minus(euros)).isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void overflowThrowsInsteadOfWrapping() {
    Money max = Money.of(Long.MAX_VALUE, GBP);
    Money min = Money.of(Long.MIN_VALUE, GBP);

    assertThatThrownBy(() -> max.plus(Money.of(1, GBP))).isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> min.minus(Money.of(1, GBP))).isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(min::negate).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void signPredicates() {
    assertThat(Money.zero(GBP).isZero()).isTrue();
    assertThat(Money.zero(GBP).isPositive()).isFalse();
    assertThat(Money.zero(GBP).isNegative()).isFalse();
    assertThat(Money.of(1, GBP).isPositive()).isTrue();
    assertThat(Money.of(-1, GBP).isNegative()).isTrue();
  }
}
