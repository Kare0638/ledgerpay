package com.ledgerpay.payment.ledger.integrity;

import com.ledgerpay.payment.ledger.integrity.Violation.Check;
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

  public LedgerIntegrityCheck(JdbcClient jdbc, PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
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

  private void journalOperationMismatches(List<Violation> violations) {
    jdbc.sql(
            """
            SELECT j.id, j.entry_type, o.amount_minor, o.currency, pay.merchant_id,
                   coalesce(sum(p.amount_minor)
                       FILTER (WHERE starts_with(p.account_id, 'psp_receivable:')), 0) AS receivable,
                   count(*) FILTER (WHERE starts_with(p.account_id, 'merchant_payable:')
                       AND p.account_id <> 'merchant_payable:' || pay.merchant_id) AS other_merchant,
                   count(*) FILTER (WHERE p.currency <> o.currency) AS other_currency
              FROM journal_entries j
              JOIN psp_operations o ON o.id = j.psp_operation_id
              JOIN payments pay ON pay.id = o.payment_id
              JOIN postings p ON p.entry_id = j.id
             GROUP BY j.id, j.entry_type, o.amount_minor, o.currency, pay.merchant_id
            HAVING coalesce(sum(p.amount_minor)
                       FILTER (WHERE starts_with(p.account_id, 'psp_receivable:')), 0)
                     <> CASE WHEN j.entry_type = 'CAPTURE' THEN o.amount_minor
                             ELSE -o.amount_minor END
                OR count(*) FILTER (WHERE starts_with(p.account_id, 'merchant_payable:')
                       AND p.account_id <> 'merchant_payable:' || pay.merchant_id) > 0
                OR count(*) FILTER (WHERE p.currency <> o.currency) > 0
             ORDER BY j.id""")
        .query(
            (rs, row) -> {
              long amount = rs.getLong("amount_minor");
              long expected = rs.getString("entry_type").equals("CAPTURE") ? amount : -amount;
              List<String> found = new ArrayList<>();
              if (rs.getLong("receivable") != expected) {
                found.add(
                    "PSP receivable moved by "
                        + rs.getLong("receivable")
                        + ", expected "
                        + expected);
              }
              if (rs.getLong("other_merchant") > 0) {
                found.add(
                    "merchant share posted to a merchant other than "
                        + rs.getString("merchant_id"));
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
                  JOIN postings p ON p.entry_id = j.id
                                 AND starts_with(p.account_id, 'psp_receivable:')
                 GROUP BY o.payment_id)
            SELECT pay.id, pay.captured_minor, pay.refunded_minor,
                   coalesce(b.captured, 0) AS booked_captured,
                   coalesce(b.refunded, 0) AS booked_refunded
              FROM payments pay LEFT JOIN booked b ON b.payment_id = pay.id
             WHERE pay.captured_minor <> coalesce(b.captured, 0)
                OR pay.refunded_minor <> coalesce(b.refunded, 0)
             ORDER BY pay.id""")
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
