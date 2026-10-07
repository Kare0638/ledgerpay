package com.ledgerpay.payment.api;

import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.ledger.Account;
import com.ledgerpay.payment.ledger.Ledger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/accounts/{id}/balance}: a merchant may read one account, its own payable. Every
 * other ID, another merchant's payable or a platform account, is 404 like an account that does not
 * exist (design §7).
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

  private final Ledger ledger;

  public AccountController(Ledger ledger) {
    this.ledger = ledger;
  }

  /**
   * @param balanceMinor derived from postings, debit positive (design §5.5): negative while the
   *     platform owes the merchant money
   */
  record BalanceView(String accountId, String currency, long balanceMinor) {}

  @GetMapping("/{id}/balance")
  BalanceView balance(
      @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant,
      @PathVariable String id) {
    String own = Account.merchantPayable(merchant.id()).id();
    if (!id.equals(own)) {
      throw new ResourceNotFoundException("Account " + id);
    }
    // Accounts are created by their first posting: before any capture the payable is zero.
    Money balance = ledger.balance(own).orElse(Money.zero(Money.GBP));
    return new BalanceView(own, balance.currency().getCurrencyCode(), balance.minor());
  }
}
