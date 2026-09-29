package com.ledgerpay.payment.ledger;

import java.util.Objects;

/**
 * A ledger account (design §5.5). Accounts are created on first use.
 *
 * <p>The chart of accounts is fixed, and the type follows from the ID:
 *
 * <pre>
 * psp_receivable:{psp}          ASSET
 * merchant_payable:{merchantId} LIABILITY
 * fee_revenue                   REVENUE
 * </pre>
 *
 * The constructor rejects any other ID or a type that does not match, so a wrong account can never
 * reach the database, not even on first use.
 */
public record Account(String id, AccountType type) {

  private static final String PSP_RECEIVABLE = "psp_receivable:";
  private static final String MERCHANT_PAYABLE = "merchant_payable:";
  private static final String FEE_REVENUE_ID = "fee_revenue";

  public static final Account FEE_REVENUE = new Account(FEE_REVENUE_ID, AccountType.REVENUE);

  public Account {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    AccountType expected = typeOf(id);
    if (type != expected) {
      throw new IllegalArgumentException(
          "account " + id + " must be " + expected + ", not " + type);
    }
  }

  /** Owed to us by the PSP between capture and settlement. */
  public static Account pspReceivable(String psp) {
    return new Account(PSP_RECEIVABLE + psp, AccountType.ASSET);
  }

  /** Owed by us to the merchant. */
  public static Account merchantPayable(String merchantId) {
    return new Account(MERCHANT_PAYABLE + merchantId, AccountType.LIABILITY);
  }

  private static AccountType typeOf(String id) {
    if (hasNonEmptySuffix(id, PSP_RECEIVABLE)) {
      return AccountType.ASSET;
    }
    if (hasNonEmptySuffix(id, MERCHANT_PAYABLE)) {
      return AccountType.LIABILITY;
    }
    if (id.equals(FEE_REVENUE_ID)) {
      return AccountType.REVENUE;
    }
    throw new IllegalArgumentException("not in the chart of accounts: " + id);
  }

  private static boolean hasNonEmptySuffix(String id, String prefix) {
    return id.startsWith(prefix) && id.length() > prefix.length();
  }
}
