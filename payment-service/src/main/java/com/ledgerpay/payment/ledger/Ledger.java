package com.ledgerpay.payment.ledger;

import com.ledgerpay.common.money.Money;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes journals and derives balances from postings (design §5.5). */
@Repository
public class Ledger {

  private final JdbcClient jdbc;

  public Ledger(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Posts one journal for a confirmed PSP operation. Must run inside the caller's money transaction
   * (design §9.3): the balance constraint is checked when that transaction commits.
   *
   * @throws org.springframework.dao.DuplicateKeyException if the operation already has a journal
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID post(UUID pspOperationId, EntryType type, List<Posting> postings) {
    requireBalanced(postings);
    UUID entryId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO journal_entries (id, psp_operation_id, entry_type) VALUES (:id, :op, :type)")
        .param("id", entryId)
        .param("op", pspOperationId)
        .param("type", type.name())
        .update();
    for (Posting posting : postings) {
      ensureAccount(posting.account(), posting.amount().currency());
      jdbc.sql(
              """
              INSERT INTO postings (entry_id, account_id, amount_minor, currency)
              VALUES (:entry, :account, :amount, :currency)""")
          .param("entry", entryId)
          .param("account", posting.account().id())
          .param("amount", posting.amount().minor())
          .param("currency", posting.amount().currency().getCurrencyCode())
          .update();
    }
    return entryId;
  }

  /** The account's balance, debit positive. Empty if the account does not exist. */
  public Optional<Money> balance(String accountId) {
    return jdbc.sql(
            """
            SELECT a.currency, coalesce(sum(p.amount_minor), 0) AS balance
            FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
            WHERE a.id = :id
            GROUP BY a.currency""")
        .param("id", accountId)
        .query(
            (rs, row) ->
                Money.of(rs.getLong("balance"), Currency.getInstance(rs.getString("currency"))))
        .optional();
  }

  /**
   * Creates the account on first use and rejects a type that disagrees with the stored one. A
   * currency mismatch is caught by the postings (account_id, currency) foreign key instead.
   *
   * <p>The type is re-read rather than returned by {@code ON CONFLICT DO UPDATE ... RETURNING},
   * which would write and lock the account row on every posting and serialise all captures on hot
   * accounts such as {@code fee_revenue}.
   */
  private void ensureAccount(Account account, Currency currency) {
    jdbc.sql(
            """
            INSERT INTO accounts (id, type, currency) VALUES (:id, :type, :currency)
            ON CONFLICT (id) DO NOTHING""")
        .param("id", account.id())
        .param("type", account.type().name())
        .param("currency", currency.getCurrencyCode())
        .update();
    String storedType =
        jdbc.sql("SELECT type FROM accounts WHERE id = :id")
            .param("id", account.id())
            .query(String.class)
            .single();
    if (!storedType.equals(account.type().name())) {
      throw new IllegalStateException(
          "account "
              + account.id()
              + " is "
              + storedType
              + " but was posted to as "
              + account.type());
    }
  }

  /** Fails fast in the application; the database checks the same rule again at commit. */
  private static void requireBalanced(List<Posting> postings) {
    if (postings.size() < 2) {
      throw new IllegalArgumentException("a journal needs at least 2 postings");
    }
    Money sum = postings.stream().map(Posting::amount).reduce(Money::plus).orElseThrow();
    if (!sum.isZero()) {
      throw new IllegalArgumentException("postings do not sum to zero: " + sum.minor());
    }
  }
}
