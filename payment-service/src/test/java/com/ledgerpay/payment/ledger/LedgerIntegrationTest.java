package com.ledgerpay.payment.ledger;

import static com.ledgerpay.common.money.Money.GBP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the ledger invariants against real PostgreSQL (design §5.5). Journals are append-only, so
 * nothing is cleaned up between tests: each test uses its own merchant, PSP name and operations.
 */
@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
class LedgerIntegrationTest {

  @Autowired Ledger ledger;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;

  String merchantId;
  String psp;
  UUID paymentId;

  @BeforeEach
  void newMerchantAndPayment() {
    merchantId = "m_" + UUID.randomUUID();
    psp = "psp-" + UUID.randomUUID();
    paymentId = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchantId, "hash-" + merchantId)
        .update();
    jdbc.sql(
            """
            INSERT INTO payments (id, merchant_id, merchant_reference, amount_minor, currency, status)
            VALUES (?, ?, 'order-1', 10000, 'GBP', 'CAPTURED')""")
        .params(paymentId, merchantId)
        .update();
  }

  UUID pspOperation(String type, long amount) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO psp_operations
                (id, payment_id, type, psp_request_id, amount_minor, currency, status)
            VALUES (?, ?, ?, ?, ?, 'GBP', 'SUCCEEDED')""")
        .params(id, paymentId, type, "req-" + id, amount)
        .update();
    return id;
  }

  long balance(Account account) {
    return ledger.balance(account.id()).map(Money::minor).orElseThrow();
  }

  long feeRevenue() {
    return ledger.balance(Account.FEE_REVENUE.id()).map(Money::minor).orElse(0L);
  }

  UUID post(UUID operation, EntryType type, List<Posting> postings) {
    return tx.execute(status -> ledger.post(operation, type, postings));
  }

  // --- The happy path ----------------------------------------------------------------------------

  @Test
  void at01CaptureOf100PoundsPostsOneJournalWithThreeBalancedPostings() {
    long feeRevenueBefore = feeRevenue();
    UUID capture = pspOperation("CAPTURE", 10000);

    UUID entry =
        post(
            capture,
            EntryType.CAPTURE,
            PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100));

    assertThat(
            jdbc.sql("SELECT count(*) FROM journal_entries WHERE psp_operation_id = ?")
                .param(capture)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT amount_minor FROM postings WHERE entry_id = ? ORDER BY id")
                .param(entry)
                .query(Long.class)
                .list())
        .containsExactly(10000L, -9900L, -100L);
    assertThat(balance(Account.pspReceivable(psp))).isEqualTo(10000);
    assertThat(balance(Account.merchantPayable(merchantId))).isEqualTo(-9900);
    assertThat(feeRevenue() - feeRevenueBefore).isEqualTo(-100);
  }

  @Test
  void refundsAfterCaptureLeaveTheRetainedFeeOwedByTheMerchant() {
    post(
        pspOperation("CAPTURE", 10000),
        EntryType.CAPTURE,
        PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100));
    post(
        pspOperation("REFUND", 3000),
        EntryType.REFUND,
        PostingRules.refund(psp, merchantId, Money.of(3000, GBP)));
    post(
        pspOperation("REFUND", 7000),
        EntryType.REFUND,
        PostingRules.refund(psp, merchantId, Money.of(7000, GBP)));

    assertThat(balance(Account.pspReceivable(psp))).isZero();
    assertThat(balance(Account.merchantPayable(merchantId))).isEqualTo(100);
  }

  @Test
  void balanceOfAnUnknownAccountIsEmpty() {
    assertThat(ledger.balance("merchant_payable:nobody")).isEmpty();
  }

  // --- Invariant 1: every journal is balanced, checked at commit ---------------------------------

  @Test
  void anUnbalancedJournalCannotCommit() {
    UUID capture = pspOperation("CAPTURE", 100);
    UUID entry = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      insertJournal(entry, capture);
                      insertPosting(entry, Account.pspReceivable(psp), 100);
                      insertPosting(entry, Account.merchantPayable(merchantId), -99);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("is unbalanced");

    assertNothingPostedFor(capture);
  }

  @Test
  void aJournalWithoutPostingsCannotCommit() {
    UUID capture = pspOperation("CAPTURE", 100);

    assertThatThrownBy(
            () -> tx.executeWithoutResult(status -> insertJournal(UUID.randomUUID(), capture)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("0 posting(s)");

    assertNothingPostedFor(capture);
  }

  @Test
  void aJournalWithASinglePostingCannotCommit() {
    UUID capture = pspOperation("CAPTURE", 100);
    UUID entry = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      insertJournal(entry, capture);
                      insertPosting(entry, Account.pspReceivable(psp), 100);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("1 posting(s)");
  }

  @Test
  void theApplicationRejectsAnUnbalancedJournalBeforeTheDatabaseDoes() {
    UUID capture = pspOperation("CAPTURE", 100);
    List<Posting> unbalanced =
        List.of(
            new Posting(Account.pspReceivable(psp), Money.of(100, GBP)),
            new Posting(Account.merchantPayable(merchantId), Money.of(-99, GBP)));

    assertThatThrownBy(() -> post(capture, EntryType.CAPTURE, unbalanced))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- Invariant 2: exactly one journal per PSP operation ----------------------------------------

  @Test
  void aSecondJournalForTheSameOperationIsRejected() {
    UUID capture = pspOperation("CAPTURE", 10000);
    List<Posting> postings = PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100);
    post(capture, EntryType.CAPTURE, postings);

    assertThatThrownBy(() -> post(capture, EntryType.CAPTURE, postings))
        .isInstanceOf(DuplicateKeyException.class);

    assertThat(balance(Account.pspReceivable(psp))).isEqualTo(10000);
  }

  // --- Invariant 3: append-only ------------------------------------------------------------------

  @Test
  void journalsAndPostingsCannotBeUpdatedDeletedOrTruncated() {
    UUID entry =
        post(
            pspOperation("CAPTURE", 10000),
            EntryType.CAPTURE,
            PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100));

    for (String sql :
        List.of(
            "UPDATE postings SET amount_minor = amount_minor * 2 WHERE entry_id = :entry",
            "DELETE FROM postings WHERE entry_id = :entry",
            "UPDATE journal_entries SET entry_type = 'REFUND' WHERE id = :entry",
            "DELETE FROM journal_entries WHERE id = :entry")) {
      assertThatThrownBy(() -> jdbc.sql(sql).param("entry", entry).update())
          .as(sql)
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("is append-only");
    }
    for (String sql : List.of("TRUNCATE postings", "TRUNCATE journal_entries CASCADE")) {
      assertThatThrownBy(() -> jdbc.sql(sql).update())
          .as(sql)
          .isInstanceOf(DataIntegrityViolationException.class)
          .rootCause()
          .hasMessageContaining("is append-only");
    }

    assertThat(
            jdbc.sql("SELECT amount_minor FROM postings WHERE entry_id = ? ORDER BY id")
                .param(entry)
                .query(Long.class)
                .list())
        .containsExactly(10000L, -9900L, -100L);
  }

  @Test
  void postingsCannotBeAddedToACommittedJournal() {
    UUID entry =
        post(
            pspOperation("CAPTURE", 10000),
            EntryType.CAPTURE,
            PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100));

    // A balanced pair would pass the balance check, so only the closed-journal rule stops it.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      insertPosting(entry, Account.pspReceivable(psp), 500);
                      insertPosting(entry, Account.merchantPayable(merchantId), -500);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("is closed");

    assertThat(balance(Account.pspReceivable(psp))).isEqualTo(10000);
  }

  // --- Invariant 4: currencies -------------------------------------------------------------------

  @Test
  void aPostingInADifferentCurrencyFromItsAccountIsRejected() {
    post(
        pspOperation("CAPTURE", 10000),
        EntryType.CAPTURE,
        PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100));
    UUID capture = pspOperation("CAPTURE", 100);
    UUID entry = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      insertJournal(entry, capture);
                      jdbc.sql(
                              """
                              INSERT INTO postings (entry_id, account_id, amount_minor, currency)
                              VALUES (?, ?, 100, 'EUR')""")
                          .params(entry, Account.pspReceivable(psp).id())
                          .update();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .rootCause()
        .hasMessageContaining("foreign key");
  }

  // --- Account types -----------------------------------------------------------------------------

  @Test
  void anAccountStoredWithTheWrongTypeIsNotPostedTo() {
    // Account's constructor stops a wrong type in Java, so plant the bad row with raw SQL, as a
    // manual fix or a faulty migration could.
    Account payable = Account.merchantPayable(merchantId);
    jdbc.sql("INSERT INTO accounts (id, type, currency) VALUES (?, 'ASSET', 'GBP')")
        .param(payable.id())
        .update();
    UUID capture = pspOperation("CAPTURE", 10000);

    assertThatThrownBy(
            () ->
                post(
                    capture,
                    EntryType.CAPTURE,
                    PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("account " + payable.id() + " is ASSET but was posted to as LIABILITY");

    assertNothingPostedFor(capture);
    assertThat(balance(payable)).isZero();
  }

  // --- Usage and ledger-wide checks --------------------------------------------------------------

  @Test
  void postingRequiresTheCallersTransaction() {
    UUID capture = pspOperation("CAPTURE", 10000);

    assertThatThrownBy(
            () ->
                ledger.post(
                    capture,
                    EntryType.CAPTURE,
                    PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100)))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void allPostingsInTheLedgerSumToZero() {
    post(
        pspOperation("CAPTURE", 10000),
        EntryType.CAPTURE,
        PostingRules.capture(psp, merchantId, Money.of(10000, GBP), 100));

    assertThat(
            jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM postings")
                .query(Long.class)
                .single())
        .isZero();
  }

  // --- Raw SQL helpers, bypassing Ledger to test the database on its own -------------------------

  void insertJournal(UUID entry, UUID operation) {
    jdbc.sql(
            "INSERT INTO journal_entries (id, psp_operation_id, entry_type) VALUES (?, ?, 'CAPTURE')")
        .params(entry, operation)
        .update();
  }

  void insertPosting(UUID entry, Account account, long minor) {
    jdbc.sql(
            """
            INSERT INTO accounts (id, type, currency) VALUES (?, ?, 'GBP')
            ON CONFLICT (id) DO NOTHING""")
        .params(account.id(), account.type().name())
        .update();
    jdbc.sql(
            """
            INSERT INTO postings (entry_id, account_id, amount_minor, currency)
            VALUES (?, ?, ?, 'GBP')""")
        .params(entry, account.id(), minor)
        .update();
  }

  void assertNothingPostedFor(UUID operation) {
    assertThat(
            jdbc.sql("SELECT count(*) FROM journal_entries WHERE psp_operation_id = ?")
                .param(operation)
                .query(Long.class)
                .single())
        .isZero();
  }
}
