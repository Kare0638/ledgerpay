package com.ledgerpay.payment.ledger;

import com.ledgerpay.common.money.Money;
import java.util.Objects;

/** One line of a journal. {@code amount} is signed: debit positive, credit negative. */
public record Posting(Account account, Money amount) {

  public Posting {
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(amount, "amount");
    if (amount.isZero()) {
      throw new IllegalArgumentException("a posting cannot be zero");
    }
  }
}
