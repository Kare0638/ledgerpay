package com.ledgerpay.payment.ledger;

import com.ledgerpay.common.money.Fees;
import com.ledgerpay.common.money.Money;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a confirmed money movement into balanced postings (design §5.5). Pure functions: the
 * database re-checks the balance at commit.
 *
 * <pre>
 * Capture £100 at 1%:  psp_receivable +10000, merchant_payable −9900, fee_revenue −100
 * Refund £30:          merchant_payable +3000, psp_receivable −3000   (the fee is not returned)
 * </pre>
 *
 * Zero lines are left out, since a posting is never zero: with a 0 bps fee there is no fee line,
 * and with a 10000 bps fee there is no merchant line.
 */
public final class PostingRules {

  private PostingRules() {}

  public static List<Posting> capture(String psp, String merchantId, Money amount, int feeBps) {
    requirePositive(amount);
    Money fee = Fees.calculate(amount, feeBps);
    Money merchantShare = amount.minus(fee);

    List<Posting> postings = new ArrayList<>(3);
    postings.add(new Posting(Account.pspReceivable(psp), amount));
    if (!merchantShare.isZero()) {
      postings.add(new Posting(Account.merchantPayable(merchantId), merchantShare.negate()));
    }
    if (!fee.isZero()) {
      postings.add(new Posting(Account.FEE_REVENUE, fee.negate()));
    }
    return List.copyOf(postings);
  }

  public static List<Posting> refund(String psp, String merchantId, Money amount) {
    requirePositive(amount);
    return List.of(
        new Posting(Account.merchantPayable(merchantId), amount),
        new Posting(Account.pspReceivable(psp), amount.negate()));
  }

  private static void requirePositive(Money amount) {
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("amount must be positive: " + amount.minor());
    }
  }
}
