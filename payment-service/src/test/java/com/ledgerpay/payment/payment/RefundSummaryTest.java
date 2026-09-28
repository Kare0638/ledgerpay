package com.ledgerpay.payment.payment;

import static com.ledgerpay.common.money.Money.GBP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerpay.common.money.CurrencyMismatchException;
import com.ledgerpay.common.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RefundSummaryTest {

  @ParameterizedTest(name = "captured {0}, refunded {1} -> {2}")
  @CsvSource({
    "10000, 0,     NONE",
    "10000, 1,     PARTIAL",
    "10000, 3000,  PARTIAL", // AT-10: £30 of £100
    "10000, 9999,  PARTIAL",
    "10000, 10000, FULL", // AT-10: then £70 more
    "1,     1,     FULL",
    "0,     0,     NONE", // not captured yet
  })
  void derivesTheSummaryFromAmounts(long captured, long refunded, RefundSummary expected) {
    assertThat(RefundSummary.of(Money.of(captured, GBP), Money.of(refunded, GBP)))
        .isEqualTo(expected);
  }

  @Test
  void rejectsRefundedAboveCaptured() {
    assertThatThrownBy(() -> RefundSummary.of(Money.of(10000, GBP), Money.of(10001, GBP)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeRefunded() {
    assertThatThrownBy(() -> RefundSummary.of(Money.of(10000, GBP), Money.of(-1, GBP)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMixedCurrencies() {
    Money euros = Money.of(100, Currency.getInstance("EUR"));

    assertThatThrownBy(() -> RefundSummary.of(Money.of(100, GBP), euros))
        .isInstanceOf(CurrencyMismatchException.class);
  }
}
