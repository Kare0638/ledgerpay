package com.ledgerpay.payment.ledger.integrity;

import com.ledgerpay.payment.ledger.Account;
import com.ledgerpay.payment.ledger.integrity.Violation.Check;
import com.ledgerpay.payment.psp.PspProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the whole ledger and checks it against itself (design §5.5, §11.2): no PSP report is
 * involved, so it finds a deleted journal or a corrupted total even on a day whose report matches
 * to the penny.
 *
 * <p>All queries run in one {@code REPEATABLE READ} read-only transaction, so a money transaction
 * committing meanwhile cannot show up as half a journal. The database already enforces most of
 * these invariants (V2); this finds what gets past them: a migration, a manual fix or a restore
 * that bypassed the triggers. "At most one journal per operation" is a unique index, which no
 * trigger switch can bypass, so it is not re-checked here.
 */
@Component
public class LedgerIntegrityCheck {

  private final JdbcClient jdbc;
  private final TransactionTemplate snapshot;
  private final String receivable;

  public LedgerIntegrityCheck(
      JdbcClient jdbc, PlatformTransactionManager transactions, PspProperties psp) {
    this.jdbc = jdbc;
    // Every payment goes through the one configured PSP, so its journals book that receivable.
    this.receivable = Account.pspReceivable(psp.provider()).id();
    this.snapshot = new TransactionTemplate(transactions);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshot.setReadOnly(true);
  }

  public IntegrityResult run() {
    return snapshot.execute(
        status -> {
          // The first statement fixes the snapshot; its clock is when the books were read.
          Instant at =
              jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
          List<Violation> violations = new ArrayList<>();
          missingJournals(violations);
          unexpectedJournals(violations);
          unbalancedJournals(violations);
          ledgerNotZero(violations);
          journalOperationMismatches(violations);
          paymentTotals(violations);
          long journals =
              jdbc.sql("SELECT count(*) FROM journal_entries").query(Long.class).single();
          long postings = jdbc.sql("SELECT count(*) FROM postings").query(Long.class).single();
          return new IntegrityResult(at, journals, postings, List.copyOf(violations));
        });
  }

  private void missingJournals(List<Violation> violations) {
    jdbc.sql(
            """
            SELECT o.id, o.type, o.payment_id FROM psp_operations o
             WHERE o.status = 'SUCCEEDED' AND o.type IN ('CAPTURE', 'REFUND')
               AND NOT EXISTS (SELECT 1 FROM journal_entries j WHERE j.psp_operation_id = o.id)
             ORDER BY o.id""")
        .query(
            (rs, row) ->
                new Violation(
                    Check.MISSING_JOURNAL,
                    "psp_operation " + rs.getString("id"),
                    "succeeded "
                        + rs.getString("type")
                        + " of payment "
                        + rs.getString("payment_id")
                        + " has no journal"))
        .list()
        .forEach(violations::add);
  }

  private void unexpectedJournals(List<Violation> violations) {
    jdbc.sql(
            """
            SELECT j.id, j.entry_type, o.id AS operation, o.type, o.status
              FROM journal_entries j JOIN psp_operations o ON o.id = j.psp_operation_id
             WHERE o.status <> 'SUCCEEDED' OR o.type <> j.entry_type
             ORDER BY j.id""")
        .query(
            (rs, row) ->
                new Violation(
                    Check.UNEXPECTED_JOURNAL,
                    "journal " + rs.getString("id"),
                    rs.getString("entry_type")
                        + " journal for "
                        + rs.getString("status")
                        + " "
                        + rs.getString("type")
                        + " "
                        + rs.getString("operation")))
        .list()
        .forEach(violations::add);
  }

  private void unbalancedJournals(List<Violation> violations) {
    jdbc.sql(
            """
            SELECT j.id, count(p.id) AS postings FROM journal_entries j
              LEFT JOIN postings p ON p.entry_id = j.id
             GROUP BY j.id HAVING count(p.id) < 2
             ORDER BY j.id""")
        .query(
            (rs, row) ->
                new Violation(
                    Check.UNBALANCED_JOURNAL,
                    "journal " + rs.getString("id"),
                    "has " + rs.getInt("postings") + " posting(s); at least 2 are required"))
        .list()
        .forEach(violations::add);
    jdbc.sql(
            """
            SELECT entry_id, currency, sum(amount_minor) AS total FROM postings
             GROUP BY entry_id, currency HAVING sum(amount_minor) <> 0
             ORDER BY entry_id, currency""")
        .query(
            (rs, row) ->
                new Violation(
                    Check.UNBALANCED_JOURNAL,
                    "journal " + rs.getString("entry_id"),
                    rs.getString("currency")
                        + " postings sum to "
                        + rs.getLong("total")
                        + ", not 0"))
        .list()
        .forEach(violations::add);
  }

  private void ledgerNotZero(List<Violation> violations) {
    jdbc.sql(
            """
            SELECT currency, sum(amount_minor) AS total FROM postings
             GROUP BY currency HAVING sum(amount_minor) <> 0 ORDER BY currency""")
        .query(
            (rs, row) ->
                new Violation(
                    Check.LEDGER_NOT_ZERO,
                    "currency " + rs.getString("currency"),
                    "all postings sum to " + rs.getLong("total") + ", not 0"))
        .list()
        .forEach(violations::add);
  }

  /**
   * Each journal must book exactly the postings the money transaction makes for its operation
   * (PostingRules): the amount to this PSP's receivable, the merchant's share to that merchant's
   * own payable, the fee to fee_revenue, nothing to any other account, all in the operation's
   * currency. A balanced journal that routes money elsewhere, all of it to fee_revenue say, is
   * caught here. The fee is recomputed from the merchant's fee_bps, with the rounding of
   * Fees.calculate; a merchant's rate is never changed, so it is the rate the capture used.
   */
  private void journalOperationMismatches(List<Violation> violations) {
    jdbc.sql(
            """
            WITH booked AS (
                SELECT j.id, j.entry_type, o.amount_minor AS amount, o.currency, pay.merchant_id,
                       :payablePrefix || pay.merchant_id AS payable_account,
                       (o.amount_minor * m.fee_bps + 5000) / 10000 AS fee,
                       coalesce(sum(p.amount_minor) FILTER (WHERE p.account_id = :receivable), 0)
                           AS receivable,
                       coalesce(sum(p.amount_minor)
                           FILTER (WHERE p.account_id = :payablePrefix || pay.merchant_id), 0)
                           AS payable,
                       coalesce(sum(p.amount_minor) FILTER (WHERE p.account_id = :feeRevenue), 0)
                           AS fee_revenue,
                       string_agg(DISTINCT p.account_id, ', ' ORDER BY p.account_id)
                           FILTER (WHERE p.account_id NOT IN
                               (:receivable, :payablePrefix || pay.merchant_id, :feeRevenue))
                           AS other_accounts,
                       count(*) FILTER (WHERE p.currency <> o.currency) AS other_currency
                  FROM journal_entries j
                  JOIN psp_operations o ON o.id = j.psp_operation_id
                  JOIN payments pay ON pay.id = o.payment_id
                  JOIN merchants m ON m.id = pay.merchant_id
                  JOIN postings p ON p.entry_id = j.id
                 GROUP BY j.id, j.entry_type, o.amount_minor, o.currency, pay.merchant_id,
                          m.fee_bps),
            expected AS (
                SELECT b.*,
                       CASE b.entry_type WHEN 'CAPTURE' THEN b.amount ELSE -b.amount END
                           AS expected_receivable,
                       CASE b.entry_type WHEN 'CAPTURE' THEN -(b.amount - b.fee) ELSE b.amount END
                           AS expected_payable,
                       CASE b.entry_type WHEN 'CAPTURE' THEN -b.fee ELSE 0 END AS expected_fee
                  FROM booked b)
            SELECT * FROM expected
             WHERE receivable <> expected_receivable
                OR payable <> expected_payable
                OR fee_revenue <> expected_fee
                OR other_accounts IS NOT NULL
                OR other_currency > 0
             ORDER BY id""")
        .param("receivable", receivable)
        .param("payablePrefix", Account.MERCHANT_PAYABLE)
        .param("feeRevenue", Account.FEE_REVENUE.id())
        .query(
            (rs, row) -> {
              List<String> found = new ArrayList<>();
              moved(
                  found,
                  "PSP receivable",
                  rs.getLong("receivable"),
                  rs.getLong("expected_receivable"));
              moved(
                  found,
                  "merchant " + rs.getString("merchant_id") + " payable",
                  rs.getLong("payable"),
                  rs.getLong("expected_payable"));
              moved(found, "fee revenue", rs.getLong("fee_revenue"), rs.getLong("expected_fee"));
              if (rs.getString("other_accounts") != null) {
                found.add(
                    "posted to accounts outside the operation's: "
                        + rs.getString("other_accounts"));
              }
              if (rs.getLong("other_currency") > 0) {
                found.add("postings not in the operation's " + rs.getString("currency"));
              }
              return new Violation(
                  Check.JOURNAL_OPERATION_MISMATCH,
                  "journal " + rs.getString("id"),
                  String.join("; ", found));
            })
        .list()
        .forEach(violations::add);
  }

  private static void moved(List<String> found, String account, long actual, long expected) {
    if (actual != expected) {
      found.add(account + " moved by " + actual + ", expected " + expected);
    }
  }

  private void paymentTotals(List<Violation> violations) {
    jdbc.sql(
            """
            WITH booked AS (
                SELECT o.payment_id,
                       coalesce(sum(p.amount_minor) FILTER (WHERE j.entry_type = 'CAPTURE'), 0)
                           AS captured,
                       coalesce(-sum(p.amount_minor) FILTER (WHERE j.entry_type = 'REFUND'), 0)
                           AS refunded
                  FROM journal_entries j
                  JOIN psp_operations o ON o.id = j.psp_operation_id
                  JOIN postings p ON p.entry_id = j.id AND p.account_id = :receivable
                 GROUP BY o.payment_id)
            SELECT pay.id, pay.captured_minor, pay.refunded_minor,
                   coalesce(b.captured, 0) AS booked_captured,
                   coalesce(b.refunded, 0) AS booked_refunded
              FROM payments pay LEFT JOIN booked b ON b.payment_id = pay.id
             WHERE pay.captured_minor <> coalesce(b.captured, 0)
                OR pay.refunded_minor <> coalesce(b.refunded, 0)
             ORDER BY pay.id""")
        .param("receivable", receivable)
        .query(
            (rs, row) ->
                new Violation(
                    Check.PAYMENT_TOTALS_MISMATCH,
                    "payment " + rs.getString("id"),
                    "captured "
                        + rs.getLong("captured_minor")
                        + " and refunded "
                        + rs.getLong("refunded_minor")
                        + ", but the journals book "
                        + rs.getLong("booked_captured")
                        + " and "
                        + rs.getLong("booked_refunded")))
        .list()
        .forEach(violations::add);
  }
}
