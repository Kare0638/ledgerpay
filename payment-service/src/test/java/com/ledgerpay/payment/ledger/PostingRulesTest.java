package com.ledgerpay.payment.ledger;

import static com.ledgerpay.common.money.Money.GBP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerpay.common.money.Money;
import org.junit.jupiter.api.Test;

class PostingRulesTest {

  static Posting line(Account account, long minor) {
    return new Posting(account, Money.of(minor, GBP));
  }

  @Test
  void at01CaptureOf100PoundsAtOnePercent() {
    assertThat(PostingRules.capture("mock-psp", "m_1", Money.of(10000, GBP), 100))
        .containsExactly(
            line(Account.pspReceivable("mock-psp"), 10000),
            line(Account.merchantPayable("m_1"), -9900),
            line(Account.FEE_REVENUE, -100));
  }

  @Test
  void captureFeeIsRoundedHalfUp() {
    assertThat(PostingRules.capture("mock-psp", "m_1", Money.of(150, GBP), 100))
        .containsExactly(
            line(Account.pspReceivable("mock-psp"), 150),
            line(Account.merchantPayable("m_1"), -148),
            line(Account.FEE_REVENUE, -2));
  }

  @Test
  void zeroFeeHasNoFeeLine() {
    assertThat(PostingRules.capture("mock-psp", "m_1", Money.of(10000, GBP), 0))
        .containsExactly(
            line(Account.pspReceivable("mock-psp"), 10000),
            line(Account.merchantPayable("m_1"), -10000));
  }

  @Test
  void fullFeeHasNoMerchantLine() {
    assertThat(PostingRules.capture("mock-psp", "m_1", Money.of(10000, GBP), 10000))
        .containsExactly(
            line(Account.pspReceivable("mock-psp"), 10000), line(Account.FEE_REVENUE, -10000));
  }

  @Test
  void onePennyCaptureAtOnePercentHasNoFee() {
    assertThat(PostingRules.capture("mock-psp", "m_1", Money.of(1, GBP), 100))
        .containsExactly(
            line(Account.pspReceivable("mock-psp"), 1), line(Account.merchantPayable("m_1"), -1));
  }

  @Test
  void refundMovesMoneyBackWithoutTheFee() {
    assertThat(PostingRules.refund("mock-psp", "m_1", Money.of(3000, GBP)))
        .containsExactly(
            line(Account.merchantPayable("m_1"), 3000),
            line(Account.pspReceivable("mock-psp"), -3000));
  }

  @Test
  void rejectsNonPositiveAmounts() {
    assertThatThrownBy(() -> PostingRules.capture("mock-psp", "m_1", Money.of(0, GBP), 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PostingRules.refund("mock-psp", "m_1", Money.of(-1, GBP)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPostingIsNeverZero() {
    assertThatThrownBy(() -> line(Account.FEE_REVENUE, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
