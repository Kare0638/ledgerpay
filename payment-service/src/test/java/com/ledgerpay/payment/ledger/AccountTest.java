package com.ledgerpay.payment.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTest {

  @Test
  void factoriesBuildTheChartOfAccounts() {
    assertThat(Account.pspReceivable("mock-psp"))
        .isEqualTo(new Account("psp_receivable:mock-psp", AccountType.ASSET));
    assertThat(Account.merchantPayable("m_1"))
        .isEqualTo(new Account("merchant_payable:m_1", AccountType.LIABILITY));
    assertThat(Account.FEE_REVENUE).isEqualTo(new Account("fee_revenue", AccountType.REVENUE));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @CsvSource({
    "psp_receivable:mock-psp, LIABILITY, ASSET",
    "psp_receivable:mock-psp, REVENUE,   ASSET",
    "merchant_payable:m_1,    ASSET,     LIABILITY",
    "merchant_payable:m_1,    REVENUE,   LIABILITY",
    "fee_revenue,             ASSET,     REVENUE",
    "fee_revenue,             LIABILITY, REVENUE",
  })
  void rejectsATypeThatDoesNotMatchTheId(String id, AccountType wrong, AccountType expected) {
    assertThatThrownBy(() -> new Account(id, wrong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("account " + id + " must be " + expected + ", not " + wrong);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "cash",
        "fee_revenue:extra",
        "psp_receivable:",
        "merchant_payable:",
        "PSP_RECEIVABLE:mock-psp",
        "psp_receivable"
      })
  void rejectsIdsOutsideTheChartOfAccounts(String id) {
    assertThatThrownBy(() -> new Account(id, AccountType.ASSET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not in the chart of accounts: " + id);
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> new Account(null, AccountType.ASSET))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Account("fee_revenue", null))
        .isInstanceOf(NullPointerException.class);
  }
}
