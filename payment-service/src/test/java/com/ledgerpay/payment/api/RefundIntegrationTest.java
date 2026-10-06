package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.psp.PspOperationWorker;
import com.ledgerpay.payment.psp.StubPsp;
import com.ledgerpay.payment.psp.StubPsp.Reply;
import com.ledgerpay.payment.webhook.InboxProcessor;
import com.ledgerpay.payment.webhook.SignedWebhooks;
import com.ledgerpay.payment.webhook.SignedWebhooks.Event;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Partial refunds over real HTTP and PostgreSQL (design §5.4): the reservation, its release on each
 * PSP answer, and the refund journal. AT-10, AT-11 and AT-12 against a scripted PSP; the acceptance
 * tests repeat AT-10 and AT-12 against the real mock-psp.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledgerpay.psp.webhook-secret=" + SignedWebhooks.SECRET)
@Import(PostgresTestcontainersConfiguration.class)
class RefundIntegrationTest {

  static final StubPsp psp = new StubPsp();

  @DynamicPropertySource
  static void pspUrl(DynamicPropertyRegistry registry) {
    registry.add("ledgerpay.psp.base-url", psp::url);
  }

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired PspOperationWorker worker;
  @Autowired InboxProcessor processor;

  String merchantId;
  String apiKey;

  @BeforeEach
  void isolate() {
    psp.reset();
    merchantId = "m_" + UUID.randomUUID();
    apiKey = createMerchant(merchantId);
    jdbc.sql("UPDATE psp_operations SET next_attempt_at = now() + interval '1 day'").update();
    jdbc.sql("UPDATE inbox_events SET status = 'PROCESSED' WHERE status = 'RECEIVED'").update();
  }

  String createMerchant(String id) {
    String key = "mk_test_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(id, ApiKeys.hash(key))
        .update();
    return key;
  }

  record Response(int status, JsonNode body, HttpHeaders headers) {
    String code() {
      return body.path("code").asText();
    }

    String field(String name) {
      return body.path(name).asText();
    }
  }

  Response exchange(
      HttpMethod method, String path, String key, String idempotencyKey, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(key);
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    var response = rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    try {
      return new Response(
          response.getStatusCode().value(),
          json.readTree(response.getBody() == null ? "{}" : response.getBody()),
          response.getHeaders());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  String requestId(UUID paymentId, String type) {
    return jdbc.sql("SELECT psp_request_id FROM psp_operations WHERE payment_id = ? AND type = ?")
        .params(paymentId, type)
        .query(String.class)
        .single();
  }

  String refundRequestId(UUID refundId) {
    return jdbc.sql("SELECT psp_request_id FROM psp_operations WHERE refund_id = ?")
        .param(refundId)
        .query(String.class)
        .single();
  }

  void deliver(Event event) {
    assertThat(SignedWebhooks.send(rest, event.json()).getStatusCode().value()).isEqualTo(200);
    processor.processAll();
  }

  /** A payment of {@code amount}, authorised and captured. */
  UUID captured(long amount) {
    String body =
        """
        {"merchantReference": "order-%s", "amountMinor": %d, "currency": "GBP"}"""
            .formatted(UUID.randomUUID(), amount);
    Response created =
        exchange(HttpMethod.POST, "/v1/payments", apiKey, UUID.randomUUID().toString(), body);
    UUID paymentId = UUID.fromString(created.field("paymentId"));
    deliver(
        Event.of("AUTHORIZE", requestId(paymentId, "AUTHORIZE"), merchantId, amount, "SUCCEEDED"));
    exchange(
        HttpMethod.POST,
        "/v1/payments/" + paymentId + "/capture",
        apiKey,
        UUID.randomUUID().toString(),
        null);
    deliver(Event.of("CAPTURE", requestId(paymentId, "CAPTURE"), merchantId, amount, "SUCCEEDED"));
    assertThat(payment(paymentId).field("status")).isEqualTo("CAPTURED");
    return paymentId;
  }

  Response refund(UUID paymentId, String key, String reference, long amount) {
    String body =
        """
        {"merchantReference": "%s", "amountMinor": %d, "reason": "customer request"}"""
            .formatted(reference, amount);
    return exchange(HttpMethod.POST, "/v1/payments/" + paymentId + "/refunds", apiKey, key, body);
  }

  Response refund(UUID paymentId, long amount) {
    return refund(paymentId, UUID.randomUUID().toString(), "refund-" + UUID.randomUUID(), amount);
  }

  UUID acceptedRefund(UUID paymentId, long amount) {
    Response response = refund(paymentId, amount);
    assertThat(response.status()).as(response.body().toString()).isEqualTo(202);
    return UUID.fromString(response.field("refundId"));
  }

  Event refundOutcome(UUID refundId, long amount, String status) {
    return Event.of("REFUND", refundRequestId(refundId), merchantId, amount, status);
  }

  Response payment(UUID paymentId) {
    return exchange(HttpMethod.GET, "/v1/payments/" + paymentId, apiKey, null, null);
  }

  Response refundView(UUID refundId) {
    return exchange(HttpMethod.GET, "/v1/refunds/" + refundId, apiKey, null, null);
  }

  /** refunded, reserved and refundable, as the API reports them. */
  List<Long> amounts(UUID paymentId) {
    JsonNode body = payment(paymentId).body();
    return List.of(
        body.path("refundedMinor").asLong(),
        body.path("refundReservedMinor").asLong(),
        body.path("refundableMinor").asLong());
  }

  long refundJournals(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM journal_entries j JOIN psp_operations o ON o.id = j.psp_operation_id
             WHERE o.payment_id = ? AND o.type = 'REFUND'""")
        .param(paymentId)
        .query(Long.class)
        .single();
  }

  /** The net of this payment's journals on {@code account}. */
  long net(UUID paymentId, String account) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(p.amount_minor), 0) FROM postings p
              JOIN journal_entries j ON j.id = p.entry_id
              JOIN psp_operations o ON o.id = j.psp_operation_id
             WHERE o.payment_id = ? AND p.account_id = ?""")
        .params(paymentId, account)
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

  // --- accepting a refund --------------------------------------------------------------------

  @Test
  void aRefundReservesItsAmountAndWritesARefundOperation() {
    UUID paymentId = captured(10_000);

    Response response = refund(paymentId, "rf-1", "refund-1", 3_000);

    assertThat(response.status()).isEqualTo(202);
    UUID refundId = UUID.fromString(response.field("refundId"));
    assertThat(response.field("paymentId")).isEqualTo(paymentId.toString());
    assertThat(response.field("status")).isEqualTo("PENDING");
    assertThat(response.field("statusUrl")).isEqualTo("/v1/refunds/" + refundId);
    assertThat(response.field("traceId")).isNotBlank();
    assertThat(amounts(paymentId)).containsExactly(0L, 3_000L, 7_000L);
    assertThat(payment(paymentId).field("status"))
        .as("refunds keep the status")
        .isEqualTo("CAPTURED");
    Map<String, Object> operation =
        jdbc.sql("SELECT type, amount_minor, status FROM psp_operations WHERE refund_id = ?")
            .param(refundId)
            .query()
            .singleRow();
    assertThat(operation)
        .containsEntry("type", "REFUND")
        .containsEntry("amount_minor", 3_000L)
        .containsEntry("status", "PENDING");
    Response view = refundView(refundId);
    assertThat(view.status()).isEqualTo(200);
    assertThat(view.field("status")).isEqualTo("PENDING");
    assertThat(view.body().path("amountMinor").asLong()).isEqualTo(3_000);
    assertThat(view.field("merchantReference")).isEqualTo("refund-1");
    assertThat(view.field("reason")).isEqualTo("customer request");
  }

  @Test
  void aRefundAboveTheRefundableAmountIs409AndReservesNothing() {
    UUID paymentId = captured(10_000);
    acceptedRefund(paymentId, 6_000);

    Response tooMuch = refund(paymentId, 4_001);

    assertThat(tooMuch.status()).isEqualTo(409);
    assertThat(tooMuch.code()).isEqualTo("REFUND_AMOUNT_EXCEEDED");
    assertThat(amounts(paymentId)).containsExactly(0L, 6_000L, 4_000L);
    assertThat(refund(paymentId, 4_000).status()).as("exactly the rest").isEqualTo(202);
  }

  @Test
  void onlyACapturedPaymentCanBeRefunded() {
    String body =
        """
        {"merchantReference": "order-%s", "amountMinor": 10000, "currency": "GBP"}"""
            .formatted(UUID.randomUUID());
    UUID paymentId =
        UUID.fromString(
            exchange(HttpMethod.POST, "/v1/payments", apiKey, UUID.randomUUID().toString(), body)
                .field("paymentId"));

    Response early = refund(paymentId, "rf-1", "refund-1", 1_000);

    assertThat(early.status()).isEqualTo(409);
    assertThat(early.code()).isEqualTo("INVALID_STATE");
    assertThat(
            jdbc.sql("SELECT count(*) FROM refunds WHERE payment_id = ?")
                .param(paymentId)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void invalidRefundRequestsAre400() {
    UUID paymentId = captured(10_000);
    String path = "/v1/payments/" + paymentId + "/refunds";

    var responses =
        List.of(
            exchange(
                HttpMethod.POST,
                path,
                apiKey,
                "k1",
                """
                {"merchantReference": "r1", "amountMinor": 0, "reason": "x"}"""),
            exchange(
                HttpMethod.POST,
                path,
                apiKey,
                "k2",
                """
                {"merchantReference": "r2", "amountMinor": 100}"""),
            exchange(
                HttpMethod.POST,
                path,
                apiKey,
                "k3",
                """
                {"amountMinor": 100, "reason": "x"}"""));

    assertThat(responses)
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(400);
              assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
            });
    assertThat(amounts(paymentId)).containsExactly(0L, 0L, 10_000L);
  }

  // --- idempotency and references ------------------------------------------------------------

  @Test
  void aRetryWithTheSameKeyReplaysAndReservesOnce() {
    UUID paymentId = captured(10_000);
    Response first = refund(paymentId, "rf-1", "refund-1", 10_000);

    Response retry = refund(paymentId, "rf-1", "refund-1", 10_000);

    assertThat(retry.status()).isEqualTo(202);
    assertThat(retry.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(retry.field("refundId")).isEqualTo(first.field("refundId"));
    assertThat(amounts(paymentId)).containsExactly(0L, 10_000L, 0L);
  }

  @Test
  void theSameReferenceUnderANewKeyReturnsTheRefundEvenWhenNothingIsLeft() {
    UUID paymentId = captured(10_000);
    Response first = refund(paymentId, "rf-1", "refund-1", 10_000);

    // The first refund reserved everything: a retry must still find it, not be told 409.
    Response again = refund(paymentId, "rf-2", "refund-1", 10_000);
    Response different = refund(paymentId, "rf-3", "refund-1", 5_000);

    assertThat(again.status()).isEqualTo(202);
    assertThat(again.field("refundId")).isEqualTo(first.field("refundId"));
    assertThat(different.status()).isEqualTo(409);
    assertThat(different.code()).isEqualTo("DUPLICATE_REFERENCE");
    assertThat(amounts(paymentId)).containsExactly(0L, 10_000L, 0L);
  }

  @Test
  void anotherMerchantsPaymentAndRefundAreNotFound() {
    UUID paymentId = captured(10_000);
    UUID refundId = acceptedRefund(paymentId, 1_000);
    String otherKey = createMerchant("m_other_" + UUID.randomUUID());

    Response post =
        exchange(
            HttpMethod.POST,
            "/v1/payments/" + paymentId + "/refunds",
            otherKey,
            "rf-x",
            """
            {"merchantReference": "steal", "amountMinor": 1000, "reason": "x"}""");
    Response get = exchange(HttpMethod.GET, "/v1/refunds/" + refundId, otherKey, null, null);

    assertThat(List.of(post, get))
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(404);
              assertThat(r.code()).isEqualTo("RESOURCE_NOT_FOUND");
            });
    assertThat(amounts(paymentId)).containsExactly(0L, 1_000L, 9_000L);
  }

  // --- acceptance tests ----------------------------------------------------------------------

  /** AT-10: £100 refunded as £30 then £70. */
  @Test
  void at10ThirtyThenSeventyRefundsInFull() {
    UUID paymentId = captured(10_000);

    UUID first = acceptedRefund(paymentId, 3_000);
    deliver(refundOutcome(first, 3_000, "SUCCEEDED"));
    assertThat(payment(paymentId).field("refundSummary")).isEqualTo("PARTIAL");
    UUID second = acceptedRefund(paymentId, 7_000);
    deliver(refundOutcome(second, 7_000, "SUCCEEDED"));

    assertThat(amounts(paymentId)).containsExactly(10_000L, 0L, 0L);
    assertThat(payment(paymentId).field("refundSummary")).isEqualTo("FULL");
    assertThat(payment(paymentId).field("status")).isEqualTo("CAPTURED");
    assertThat(refundJournals(paymentId)).isEqualTo(2);
    assertThat(net(paymentId, "psp_receivable:mock-psp")).as("receivable").isZero();
    // The 1% fee is not returned: the merchant owes it back after a full refund.
    assertThat(net(paymentId, "merchant_payable:" + merchantId)).isEqualTo(100);
    assertThat(net(paymentId, "fee_revenue")).isEqualTo(-100);
    assertThat(List.of(refundView(first), refundView(second)))
        .allSatisfy(r -> assertThat(r.field("status")).isEqualTo("SUCCEEDED"));
    assertThat(outboxEvents(paymentId)).filteredOn("RefundSucceeded"::equals).hasSize(2);
  }

  /** AT-11: £70 refundable, then £50 and £40 at once: at most one is accepted. */
  @Test
  void at11ConcurrentRefundsNeverExceedTheRefundableAmount() throws Exception {
    UUID paymentId = captured(10_000);
    deliver(refundOutcome(acceptedRefund(paymentId, 3_000), 3_000, "SUCCEEDED"));
    assertThat(amounts(paymentId)).containsExactly(3_000L, 0L, 7_000L);

    CountDownLatch start = new CountDownLatch(1);
    List<Future<Response>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      for (long amount : new long[] {5_000, 4_000}) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return refund(paymentId, amount);
                }));
      }
      start.countDown();
      List<Response> responses = new ArrayList<>();
      for (var result : results) {
        responses.add(result.get());
      }

      assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(202, 409);
      assertThat(responses)
          .filteredOn(r -> r.status() == 409)
          .extracting(Response::code)
          .containsExactly("REFUND_AMOUNT_EXCEEDED");
    }
    List<Long> amounts = amounts(paymentId);
    assertThat(amounts.get(0) + amounts.get(1)).isLessThanOrEqualTo(10_000);
    assertThat(
            jdbc.sql("SELECT count(*) FROM refunds WHERE payment_id = ? AND status = 'PENDING'")
                .param(paymentId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  /** AT-12, first half: a timeout keeps the reservation, and a failure then releases it only. */
  @Test
  void at12AnUnknownOutcomeKeepsTheReservationAndAFailureReleasesOnlyThat() {
    UUID paymentId = captured(10_000);
    UUID refundId = acceptedRefund(paymentId, 4_000);
    psp.respond(request -> Reply.of(503, "{\"code\": \"UNAVAILABLE\"}"));

    worker.runOnce();

    assertThat(amounts(paymentId)).as("unknown").containsExactly(0L, 4_000L, 6_000L);
    assertThat(refundView(refundId).field("status")).isEqualTo("PENDING");

    deliver(refundOutcome(refundId, 4_000, "FAILED"));

    assertThat(amounts(paymentId)).as("failed").containsExactly(0L, 0L, 10_000L);
    assertThat(refundView(refundId).field("status")).isEqualTo("FAILED");
    assertThat(refundJournals(paymentId)).isZero();
    assertThat(outboxEvents(paymentId)).contains("RefundFailed").doesNotContain("RefundSucceeded");
  }

  /** AT-12, second half: after a timeout, success releases the reservation and books once. */
  @Test
  void at12ASuccessAfterATimeoutReleasesTheReservationAndBooksOnce() {
    UUID paymentId = captured(10_000);
    UUID refundId = acceptedRefund(paymentId, 4_000);
    psp.respond(request -> Reply.of(503, ""));
    worker.runOnce();
    assertThat(amounts(paymentId)).containsExactly(0L, 4_000L, 6_000L);

    Event succeeded = refundOutcome(refundId, 4_000, "SUCCEEDED");
    deliver(succeeded);
    deliver(succeeded.withEventId("evt_" + UUID.randomUUID()));

    assertThat(amounts(paymentId)).containsExactly(4_000L, 0L, 6_000L);
    assertThat(refundView(refundId).field("status")).isEqualTo("SUCCEEDED");
    assertThat(refundJournals(paymentId)).isEqualTo(1);
    assertThat(net(paymentId, "psp_receivable:mock-psp")).isEqualTo(10_000 - 4_000);
    assertThat(outboxEvents(paymentId)).filteredOn("RefundSucceeded"::equals).hasSize(1);
  }

  @Test
  void theWorkerSendsARefundWithTheCaptureAsItsParent() throws Exception {
    UUID paymentId = captured(10_000);
    UUID refundId = acceptedRefund(paymentId, 2_500);
    String requestId = refundRequestId(refundId);
    psp.respond(request -> Reply.of(201, StubPsp.operation(requestId, "psp_rf", "PENDING")));

    worker.runOnce();

    var submits = psp.requestsFor(requestId).stream().filter(StubPsp.Request::isSubmit).toList();
    assertThat(submits).hasSize(1);
    JsonNode sent = json.readTree(submits.getFirst().body());
    assertThat(sent.path("type").asText()).isEqualTo("REFUND");
    assertThat(sent.path("amount_minor").asLong()).isEqualTo(2_500);
    assertThat(sent.path("parent_reference").asText())
        .isEqualTo("psp_" + requestId(paymentId, "CAPTURE"));
  }
}
