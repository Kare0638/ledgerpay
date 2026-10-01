package com.ledgerpay.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerpay.common.money.Money;
import com.ledgerpay.common.webhook.WebhookSignature;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.payment.MoneyTransaction;
import com.ledgerpay.payment.payment.PaymentService;
import com.ledgerpay.payment.psp.ClaimedOperation;
import com.ledgerpay.payment.psp.PspOperationType;
import com.ledgerpay.payment.psp.PspResult;
import com.ledgerpay.payment.webhook.SignedWebhooks.Event;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /webhooks/psp}, the inbox and the money transaction (design §9.2, §9.3) over real
 * HTTP and PostgreSQL. The processor is driven explicitly.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledgerpay.psp.webhook-secret=" + SignedWebhooks.SECRET)
@Import(PostgresTestcontainersConfiguration.class)
class WebhookInboxIntegrationTest {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired PaymentService payments;
  @Autowired MoneyTransaction money;
  @Autowired InboxProcessor processor;

  String merchant;

  @BeforeEach
  void isolate() {
    merchant = "m_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, "hash_" + merchant)
        .update();
    // Events left RECEIVED by an earlier test must not be processed in this one.
    jdbc.sql("UPDATE inbox_events SET status = 'PROCESSED' WHERE status = 'RECEIVED'").update();
  }

  record Created(UUID paymentId, String authRequestId) {}

  Created newPayment(long amount) {
    UUID paymentId =
        tx.execute(
            status ->
                payments
                    .create(merchant, "ref_" + UUID.randomUUID(), Money.of(amount, Money.GBP))
                    .id());
    return new Created(paymentId, requestId(paymentId, "AUTHORIZE"));
  }

  String requestId(UUID paymentId, String type) {
    return jdbc.sql("SELECT psp_request_id FROM psp_operations WHERE payment_id = ? AND type = ?")
        .params(paymentId, type)
        .query(String.class)
        .single();
  }

  /** Delivers the event and processes the inbox, as the PSP and the scheduler would. */
  void deliver(Event event) {
    assertThat(SignedWebhooks.send(rest, event.json()).getStatusCode().value()).isEqualTo(200);
    processor.processAll();
  }

  Created authorised(long amount) {
    Created created = newPayment(amount);
    deliver(Event.of("AUTHORIZE", created.authRequestId(), merchant, amount, "SUCCEEDED"));
    return created;
  }

  String captureRequested(Created created) {
    tx.executeWithoutResult(status -> payments.capture(merchant, created.paymentId()));
    return requestId(created.paymentId(), "CAPTURE");
  }

  Map<String, Object> payment(UUID id) {
    return jdbc.sql("SELECT status, captured_minor FROM payments WHERE id = ?")
        .param(id)
        .query()
        .singleRow();
  }

  Map<String, Object> operation(String pspRequestId) {
    return jdbc.sql(
            """
            SELECT status, psp_reference, resource_version, last_error, succeeded_at IS NOT NULL AS has_succeeded_at
              FROM psp_operations WHERE psp_request_id = ?""")
        .param(pspRequestId)
        .query()
        .singleRow();
  }

  Map<String, Object> inbox(String eventId) {
    return jdbc.sql(
            "SELECT status, error, payload->>'amount_minor' AS amount FROM inbox_events WHERE event_id = ?")
        .param(eventId)
        .query()
        .singleRow();
  }

  long journals(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM journal_entries j JOIN psp_operations o ON o.id = j.psp_operation_id
             WHERE o.payment_id = ?""")
        .param(paymentId)
        .query(Long.class)
        .single();
  }

  List<String> outboxEvents(UUID paymentId) {
    return jdbc.sql(
            "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at")
        .param(paymentId)
        .query(String.class)
        .list();
  }

  long balance(String account) {
    return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM postings WHERE account_id = ?")
        .param(account)
        .query(Long.class)
        .single();
  }

  // --- the endpoint --------------------------------------------------------------------------

  @Test
  void aSignedWebhookIsStoredAndAcknowledged() {
    Created created = newPayment(10_000);
    Event event = Event.of("AUTHORIZE", created.authRequestId(), merchant, 10_000, "SUCCEEDED");

    var response = SignedWebhooks.send(rest, event.json());

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(inbox(event.eventId()).get("status")).isEqualTo("RECEIVED");
    // Acknowledging is not applying: nothing changes until the processor runs.
    assertThat(payment(created.paymentId()).get("status")).isEqualTo("AUTH_PENDING");
  }

  @Test
  void badSignaturesAre401AndNothingIsStored() {
    Event event = Event.of("AUTHORIZE", "req_x", merchant, 100, "SUCCEEDED");
    String now = String.valueOf(Instant.now().getEpochSecond());
    String stale = String.valueOf(Instant.now().minusSeconds(301).getEpochSecond());
    String validNow =
        WebhookSignature.sign(
            SignedWebhooks.SECRET.getBytes(StandardCharsets.UTF_8),
            Long.parseLong(now),
            event.json().getBytes(StandardCharsets.UTF_8));
    String validStale =
        WebhookSignature.sign(
            SignedWebhooks.SECRET.getBytes(StandardCharsets.UTF_8),
            Long.parseLong(stale),
            event.json().getBytes(StandardCharsets.UTF_8));

    var responses =
        List.of(
            SignedWebhooks.send(rest, event.json(), null, null),
            SignedWebhooks.send(rest, event.json(), now, "0".repeat(64)),
            SignedWebhooks.send(rest, event.json(), stale, validStale),
            SignedWebhooks.send(
                rest,
                event.json().replace("\"amount_minor\":100", "\"amount_minor\":999"),
                now,
                validNow));

    assertThat(responses)
        .allSatisfy(
            r -> {
              assertThat(r.getStatusCode().value()).isEqualTo(401);
              assertThat(r.getBody()).contains("\"code\":\"UNAUTHENTICATED\"");
            });
    assertThat(
            jdbc.sql("SELECT count(*) FROM inbox_events WHERE event_id = ?")
                .param(event.eventId())
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void invalidBodiesAre400() {
    var notJson = SignedWebhooks.send(rest, "{not json");
    var pending =
        SignedWebhooks.send(rest, Event.of("AUTHORIZE", "req_x", merchant, 100, "PENDING").json());
    var fractional =
        SignedWebhooks.send(
            rest,
            Event.of("AUTHORIZE", "req_x", merchant, 100, "SUCCEEDED")
                .json()
                .replace("\"amount_minor\":100", "\"amount_minor\":100.5"));
    var missingField =
        SignedWebhooks.send(
            rest,
            Event.of("AUTHORIZE", "req_x", merchant, 100, "SUCCEEDED")
                .json()
                .replace("\"merchant_id\":", "\"merchant\":"));

    assertThat(List.of(notJson, pending, fractional, missingField))
        .allSatisfy(
            r -> {
              assertThat(r.getStatusCode().value()).isEqualTo(400);
              assertThat(r.getBody()).contains("\"code\":\"VALIDATION_ERROR\"");
            });
  }

  @Test
  void aRedeliveryIsAcknowledgedAndStoredOnce() {
    Created created = newPayment(10_000);
    Event event = Event.of("AUTHORIZE", created.authRequestId(), merchant, 10_000, "SUCCEEDED");

    for (int i = 0; i < 10; i++) {
      assertThat(SignedWebhooks.send(rest, event.json()).getStatusCode().value()).isEqualTo(200);
    }

    assertThat(
            jdbc.sql("SELECT count(*) FROM inbox_events WHERE event_id = ?")
                .param(event.eventId())
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void aConflictingEventWithTheSameIdKeepsTheOriginal() {
    Created created = newPayment(10_000);
    Event original = Event.of("AUTHORIZE", created.authRequestId(), merchant, 10_000, "SUCCEEDED");
    SignedWebhooks.send(rest, original.json());

    var conflicting = SignedWebhooks.send(rest, original.withAmount(1).json());

    assertThat(conflicting.getStatusCode().value()).isEqualTo(200);
    assertThat(inbox(original.eventId()).get("amount")).isEqualTo("10000");
  }

  // --- the money transaction -----------------------------------------------------------------

  @Test
  void authorisationMovesThePaymentToAuthorisedWithoutAJournal() {
    Created created = newPayment(10_000);
    Event event = Event.of("AUTHORIZE", created.authRequestId(), merchant, 10_000, "SUCCEEDED");

    deliver(event);

    assertThat(payment(created.paymentId()).get("status")).isEqualTo("AUTHORIZED");
    var op = operation(created.authRequestId());
    assertThat(op.get("status")).isEqualTo("SUCCEEDED");
    assertThat(op.get("psp_reference")).isEqualTo("psp_" + created.authRequestId());
    assertThat(op.get("resource_version")).isEqualTo(2);
    assertThat(op.get("has_succeeded_at")).isEqualTo(true);
    assertThat(inbox(event.eventId()).get("status")).isEqualTo("PROCESSED");
    assertThat(journals(created.paymentId())).isZero();
    assertThat(outboxEvents(created.paymentId()))
        .containsExactly("PaymentCreated", "PaymentAuthorized");
  }

  @Test
  void aDeclinedAuthorisationDeclinesThePayment() {
    Created created = newPayment(10_000);

    deliver(Event.of("AUTHORIZE", created.authRequestId(), merchant, 10_000, "FAILED"));

    assertThat(payment(created.paymentId()).get("status")).isEqualTo("DECLINED");
    var op = operation(created.authRequestId());
    assertThat(op.get("status")).isEqualTo("FAILED");
    assertThat((String) op.get("last_error")).startsWith("PSP rejected");
    assertThat(outboxEvents(created.paymentId())).last().isEqualTo("PaymentDeclined");
  }

  @Test
  void captureIsBookedOnceWithBalancedPostings() {
    // AT-01: authorise and capture £100 at 1%.
    Created created = authorised(10_000);
    String capture = captureRequested(created);
    String psp = "psp_receivable:mock-psp";
    long receivableBefore = balance(psp);
    long feesBefore = balance("fee_revenue");

    deliver(Event.of("CAPTURE", capture, merchant, 10_000, "SUCCEEDED"));

    var row = payment(created.paymentId());
    assertThat(row.get("status")).isEqualTo("CAPTURED");
    assertThat(row.get("captured_minor")).isEqualTo(10_000L);
    assertThat(journals(created.paymentId())).isEqualTo(1);
    assertThat(balance(psp) - receivableBefore).isEqualTo(10_000);
    assertThat(balance("merchant_payable:" + merchant)).isEqualTo(-9_900);
    assertThat(balance("fee_revenue") - feesBefore).isEqualTo(-100);
    assertThat(outboxEvents(created.paymentId())).last().isEqualTo("PaymentCaptured");
  }

  @Test
  void aRejectedCaptureReturnsThePaymentToAuthorised() {
    Created created = authorised(10_000);
    String capture = captureRequested(created);

    deliver(Event.of("CAPTURE", capture, merchant, 10_000, "FAILED"));

    assertThat(payment(created.paymentId()).get("status")).isEqualTo("AUTHORIZED");
    assertThat(payment(created.paymentId()).get("captured_minor")).isEqualTo(0L);
    assertThat(journals(created.paymentId())).isZero();
  }

  @Test
  void aVoidMovesNoMoney() {
    Created created = authorised(10_000);
    tx.executeWithoutResult(status -> payments.voidPayment(merchant, created.paymentId()));

    deliver(
        Event.of("VOID", requestId(created.paymentId(), "VOID"), merchant, 10_000, "SUCCEEDED"));

    assertThat(payment(created.paymentId()).get("status")).isEqualTo("VOIDED");
    assertThat(journals(created.paymentId())).isZero();
  }

  @Test
  void theSameOutcomeUnderAnotherEventIdChangesNothing() {
    Created created = authorised(10_000);
    String capture = captureRequested(created);
    Event first = Event.of("CAPTURE", capture, merchant, 10_000, "SUCCEEDED");
    deliver(first);

    Event again = first.withEventId("evt_" + UUID.randomUUID());
    deliver(again);

    assertThat(journals(created.paymentId())).isEqualTo(1);
    assertThat(inbox(again.eventId()).get("status")).isEqualTo("PROCESSED");
    assertThat(inbox(again.eventId()).get("error")).isEqualTo("Already applied");
    assertThat(outboxEvents(created.paymentId())).filteredOn("PaymentCaptured"::equals).hasSize(1);
  }

  @Test
  void aContradictingOutcomeIsQuarantinedAndTheFinalStateKept() {
    Created created = authorised(10_000);
    String capture = captureRequested(created);
    Event succeeded = Event.of("CAPTURE", capture, merchant, 10_000, "SUCCEEDED");
    deliver(succeeded);

    Event failed = succeeded.withEventId("evt_" + UUID.randomUUID()).withStatus("FAILED");
    deliver(failed);

    assertThat(inbox(failed.eventId()).get("status")).isEqualTo("QUARANTINED");
    assertThat(payment(created.paymentId()).get("status")).isEqualTo("CAPTURED");
    assertThat(operation(capture).get("status")).isEqualTo("SUCCEEDED");
    assertThat(journals(created.paymentId())).isEqualTo(1);
  }

  @Test
  void eventsThatDoNotMatchOurRecordsAreQuarantinedAndTouchNothing() {
    Created created = authorised(10_000);
    String capture = captureRequested(created);
    Event good = Event.of("CAPTURE", capture, merchant, 10_000, "SUCCEEDED");
    List<Event> bad =
        List.of(
            good.withEventId("evt_amount_" + UUID.randomUUID()).withAmount(9_999),
            good.withEventId("evt_merchant_" + UUID.randomUUID()).withMerchant("m_other"),
            Event.of("VOID", capture, merchant, 10_000, "SUCCEEDED"),
            Event.of("CAPTURE", "req_unknown_" + UUID.randomUUID(), merchant, 10_000, "SUCCEEDED"));

    bad.forEach(this::deliver);

    for (Event event : bad) {
      assertThat(inbox(event.eventId()).get("status")).as(event.eventId()).isEqualTo("QUARANTINED");
      assertThat((String) inbox(event.eventId()).get("error")).isNotBlank();
    }
    assertThat(payment(created.paymentId()).get("status")).isEqualTo("CAPTURE_PENDING");
    assertThat(operation(capture).get("status")).isEqualTo("PENDING");
    assertThat(journals(created.paymentId())).isZero();
  }

  @Test
  void anOlderResourceVersionIsIgnored() {
    Created created = authorised(10_000);
    String capture = captureRequested(created);
    jdbc.sql("UPDATE psp_operations SET resource_version = 5 WHERE psp_request_id = ?")
        .param(capture)
        .update();
    Event stale =
        Event.of("CAPTURE", capture, merchant, 10_000, "SUCCEEDED").withResourceVersion(3);

    deliver(stale);

    assertThat(inbox(stale.eventId()).get("error")).isEqualTo("Stale: older resource_version");
    assertThat(operation(capture).get("status")).isEqualTo("PENDING");
  }

  @Test
  void aWebhookRacingAnInquiryBooksTheCaptureOnce() throws Exception {
    Created created = authorised(10_000);
    String capture = captureRequested(created);
    UUID opId =
        jdbc.sql("SELECT id FROM psp_operations WHERE psp_request_id = ?")
            .param(capture)
            .query(UUID.class)
            .single();
    var claimed =
        new ClaimedOperation(
            opId,
            created.paymentId(),
            PspOperationType.CAPTURE,
            capture,
            merchant,
            10_000,
            "GBP",
            null,
            "psp_" + created.authRequestId(),
            2);
    var inquiry =
        new PspResult.Final("psp_" + capture, true, null, Instant.parse("2026-10-01T09:00:00Z"), 2);
    Event webhook = Event.of("CAPTURE", capture, merchant, 10_000, "SUCCEEDED");
    SignedWebhooks.send(rest, webhook.json());

    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> racers = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      racers.add(
          pool.submit(
              () -> {
                start.await();
                return processor.processAll();
              }));
      racers.add(
          pool.submit(
              () -> {
                start.await();
                money.apply(claimed, inquiry);
                return null;
              }));
      start.countDown();
      for (var racer : racers) {
        racer.get();
      }
    }

    assertThat(journals(created.paymentId())).isEqualTo(1);
    assertThat(payment(created.paymentId()).get("status")).isEqualTo("CAPTURED");
    assertThat(inbox(webhook.eventId()).get("status")).isEqualTo("PROCESSED");
  }
}
