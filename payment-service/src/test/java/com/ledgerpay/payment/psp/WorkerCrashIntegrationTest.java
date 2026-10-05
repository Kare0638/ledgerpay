package com.ledgerpay.payment.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.outbox.Outbox;
import com.ledgerpay.payment.payment.MoneyTransaction;
import com.ledgerpay.payment.payment.PaymentService;
import com.ledgerpay.payment.psp.StubPsp.Reply;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AT-13: the worker dies before or after the money transaction commits. The death is simulated by
 * the last write of the transaction throwing, after the journal has been written but before commit;
 * "after" is a commit followed by the same outcome arriving again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfiguration.class)
class WorkerCrashIntegrationTest {

  static final StubPsp psp = new StubPsp();

  @DynamicPropertySource
  static void pspUrl(DynamicPropertyRegistry registry) {
    registry.add("ledgerpay.psp.base-url", psp::url);
  }

  @Autowired PspOperationWorker worker;
  @Autowired PaymentService payments;
  @Autowired MoneyTransaction money;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @MockitoSpyBean Outbox outbox;

  String merchant;
  UUID paymentId;
  String capture;

  /** An authorised £100 payment with a pending CAPTURE, ready for the worker. */
  @BeforeEach
  void captureRequested() {
    psp.reset();
    jdbc.sql("UPDATE psp_operations SET next_attempt_at = now() + interval '1 day'").update();
    merchant = "m_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, "hash_" + merchant)
        .update();
    paymentId =
        tx.execute(
            status ->
                payments
                    .create(merchant, "ref_" + UUID.randomUUID(), Money.of(10_000, Money.GBP))
                    .id());
    jdbc.sql(
            """
            UPDATE psp_operations SET status = 'SUCCEEDED', psp_reference = 'psp_auth_' || id,
                   succeeded_at = now() WHERE payment_id = ?""")
        .param(paymentId)
        .update();
    jdbc.sql("UPDATE payments SET status = 'AUTHORIZED' WHERE id = ?").param(paymentId).update();
    tx.executeWithoutResult(status -> payments.capture(merchant, paymentId));
    capture =
        jdbc.sql(
                "SELECT psp_request_id FROM psp_operations WHERE payment_id = ? AND type = 'CAPTURE'")
            .param(paymentId)
            .query(String.class)
            .single();
    // The PSP captured it: submit and inquiry both answer SUCCEEDED.
    psp.respond(
        request ->
            Reply.of(
                request.isSubmit() ? 201 : 200,
                StubPsp.operation(capture, "psp_" + capture, "SUCCEEDED")));
  }

  Map<String, Object> state() {
    return jdbc.sql(
            """
            SELECT p.status AS payment, p.captured_minor, o.status AS operation, o.attempts,
                   (SELECT count(*) FROM journal_entries j WHERE j.psp_operation_id = o.id) AS journals,
                   (SELECT count(*) FROM outbox_events e
                     WHERE e.aggregate_id = p.id AND e.event_type = 'PaymentCaptured') AS captured_events
              FROM payments p JOIN psp_operations o ON o.payment_id = p.id AND o.type = 'CAPTURE'
             WHERE p.id = ?""")
        .param(paymentId)
        .query()
        .singleRow();
  }

  void retryIsDue() {
    jdbc.sql(
            """
            UPDATE psp_operations SET lease_until = NULL, next_attempt_at = now() - interval '1 second'
             WHERE psp_request_id = ?""")
        .param(capture)
        .update();
  }

  @Test
  void killedBeforeTheMoneyCommitRollsBackFullyAndTheRetryBooksOnce() {
    // Stubbed inside a transaction: the spy sits behind Outbox's MANDATORY transaction proxy.
    tx.executeWithoutResult(
        status ->
            doThrow(new IllegalStateException("worker killed before commit"))
                .doCallRealMethod()
                .when(outbox)
                .append(any(), eq("PaymentCaptured"), any()));

    worker.runOnce();

    var afterCrash = state();
    assertThat(afterCrash.get("payment")).isEqualTo("CAPTURE_PENDING");
    assertThat(afterCrash.get("captured_minor")).isEqualTo(0L);
    assertThat(afterCrash.get("operation")).isEqualTo("PENDING");
    assertThat(afterCrash.get("journals")).as("journal rolled back").isEqualTo(0L);
    assertThat(afterCrash.get("captured_events")).isEqualTo(0L);

    // Its lease expires and the next attempt inquires: the PSP already has the capture.
    retryIsDue();
    worker.runOnce();

    var afterRetry = state();
    assertThat(afterRetry.get("payment")).isEqualTo("CAPTURED");
    assertThat(afterRetry.get("captured_minor")).isEqualTo(10_000L);
    assertThat(afterRetry.get("operation")).isEqualTo("SUCCEEDED");
    assertThat(afterRetry.get("journals")).isEqualTo(1L);
    assertThat(afterRetry.get("captured_events")).isEqualTo(1L);
    assertThat(psp.requestsFor(capture))
        .extracting(StubPsp.Request::method)
        .containsExactly("POST", "GET");
  }

  @Test
  void killedAfterTheMoneyCommitARedeliveryAddsNoJournal() {
    worker.runOnce();
    assertThat(state().get("journals")).isEqualTo(1L);

    // The same outcome again: the PSP's webhook redelivered, and the inquiry re-run by a worker
    // that died after committing but before it could tell anyone.
    var redelivered =
        money.apply(
            new MoneyTransaction.Outcome(
                capture,
                "psp_" + capture,
                merchant,
                PspOperationType.CAPTURE,
                Money.of(10_000, Money.GBP),
                MoneyTransaction.ReportedStatus.SUCCEEDED,
                null,
                Instant.parse("2026-10-01T09:00:00Z"),
                2));
    var claimed =
        new ClaimedOperation(
            UUID.randomUUID(),
            paymentId,
            PspOperationType.CAPTURE,
            capture,
            merchant,
            10_000,
            "GBP",
            "psp_" + capture,
            null,
            2,
            0);
    money.apply(
        claimed,
        new PspResult.Final(
            "psp_" + capture, true, null, Instant.parse("2026-10-01T09:00:00Z"), 2));
    retryIsDue();
    int reclaimed = worker.runOnce();

    assertThat(redelivered).isInstanceOf(MoneyTransaction.Result.AlreadyApplied.class);
    assertThat(reclaimed).as("a final operation is never claimed again").isZero();
    var state = state();
    assertThat(state.get("payment")).isEqualTo("CAPTURED");
    assertThat(state.get("journals")).isEqualTo(1L);
    assertThat(state.get("captured_events")).isEqualTo(1L);
  }
}
