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
 * Capture and void over real HTTP and PostgreSQL, including AT-05 and the happy path from create to
 * captured through the worker, a scripted PSP and signed webhooks.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ledgerpay.psp.webhook-secret=" + SignedWebhooks.SECRET)
@Import(PostgresTestcontainersConfiguration.class)
class CaptureVoidIntegrationTest {

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

    String status(String field) {
      return body.path(field).asText();
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

  UUID create(long amount) {
    String body =
        """
        {"merchantReference": "order-%s", "amountMinor": %d, "currency": "GBP"}"""
            .formatted(UUID.randomUUID(), amount);
    Response response =
        exchange(HttpMethod.POST, "/v1/payments", apiKey, UUID.randomUUID().toString(), body);
    assertThat(response.status()).isEqualTo(202);
    return UUID.fromString(response.status("paymentId"));
  }

  Response capture(UUID paymentId, String key) {
    return exchange(HttpMethod.POST, "/v1/payments/" + paymentId + "/capture", apiKey, key, null);
  }

  Response voidPayment(UUID paymentId, String key) {
    return exchange(HttpMethod.POST, "/v1/payments/" + paymentId + "/void", apiKey, key, null);
  }

  String status(UUID paymentId) {
    return exchange(HttpMethod.GET, "/v1/payments/" + paymentId, apiKey, null, null)
        .status("status");
  }

  String requestId(UUID paymentId, String type) {
    return jdbc.sql("SELECT psp_request_id FROM psp_operations WHERE payment_id = ? AND type = ?")
        .params(paymentId, type)
        .query(String.class)
        .single();
  }

  long operations(UUID paymentId, String type) {
    return jdbc.sql("SELECT count(*) FROM psp_operations WHERE payment_id = ? AND type = ?")
        .params(paymentId, type)
        .query(Long.class)
        .single();
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

  void confirm(UUID paymentId, String type, long amount) {
    Event event = Event.of(type, requestId(paymentId, type), merchantId, amount, "SUCCEEDED");
    assertThat(SignedWebhooks.send(rest, event.json()).getStatusCode().value()).isEqualTo(200);
    processor.processAll();
  }

  UUID authorised(long amount) {
    UUID paymentId = create(amount);
    confirm(paymentId, "AUTHORIZE", amount);
    assertThat(status(paymentId)).isEqualTo("AUTHORIZED");
    return paymentId;
  }

  // --- capture -------------------------------------------------------------------------------

  @Test
  void captureOfAnAuthorisedPaymentIsAccepted() {
    UUID paymentId = authorised(10_000);

    Response response = capture(paymentId, "cap-1");

    assertThat(response.status()).isEqualTo(202);
    assertThat(response.status("paymentId")).isEqualTo(paymentId.toString());
    assertThat(response.status("status")).isEqualTo("CAPTURE_PENDING");
    assertThat(response.status("statusUrl")).isEqualTo("/v1/payments/" + paymentId);
    assertThat(response.status("traceId")).isNotBlank();
    assertThat(status(paymentId)).isEqualTo("CAPTURE_PENDING");
    assertThat(
            jdbc.sql(
                    "SELECT amount_minor FROM psp_operations WHERE payment_id = ? AND type = 'CAPTURE'")
                .param(paymentId)
                .query(Long.class)
                .single())
        .isEqualTo(10_000);
  }

  @Test
  void captureBeforeAuthorisationIs409AndDoesNotConsumeTheKey() {
    UUID paymentId = create(10_000);

    Response tooEarly = capture(paymentId, "cap-1");

    assertThat(tooEarly.status()).isEqualTo(409);
    assertThat(tooEarly.code()).isEqualTo("INVALID_STATE");
    assertThat(operations(paymentId, "CAPTURE")).isZero();
    confirm(paymentId, "AUTHORIZE", 10_000);
    assertThat(capture(paymentId, "cap-1").status()).isEqualTo(202);
  }

  @Test
  void anotherMerchantsPaymentIsNotFound() {
    UUID paymentId = authorised(10_000);
    String otherKey = createMerchant("m_other_" + UUID.randomUUID());

    Response response =
        exchange(
            HttpMethod.POST, "/v1/payments/" + paymentId + "/capture", otherKey, "cap-1", null);

    assertThat(response.status()).isEqualTo(404);
    assertThat(response.code()).isEqualTo("RESOURCE_NOT_FOUND");
    assertThat(status(paymentId)).isEqualTo("AUTHORIZED");
  }

  @Test
  void aRetriedCaptureReplaysTheFirstResponse() {
    UUID paymentId = authorised(10_000);
    Response first = capture(paymentId, "cap-1");

    Response retry = capture(paymentId, "cap-1");

    assertThat(retry.status()).isEqualTo(202);
    assertThat(retry.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(retry.status("status")).isEqualTo(first.status("status"));
    assertThat(operations(paymentId, "CAPTURE")).isEqualTo(1);
  }

  @Test
  void theSameKeyForAnotherPaymentIsRejected() {
    UUID first = authorised(10_000);
    UUID second = authorised(5_000);
    capture(first, "cap-1");

    Response reused = capture(second, "cap-1");

    assertThat(reused.status()).isEqualTo(422);
    assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(operations(second, "CAPTURE")).isZero();
  }

  @Test
  void concurrentCapturesAcceptOneAndBookOnce() throws Exception {
    // AT-05: two (here ten) concurrent captures with different keys.
    UUID paymentId = authorised(10_000);
    int threads = 10;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Response>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        String key = "cap-" + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return capture(paymentId, key);
                }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (var future : futures) {
        statuses.add(future.get().status());
      }

      assertThat(statuses).containsOnlyOnce(202);
      assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
    }
    assertThat(operations(paymentId, "CAPTURE")).isEqualTo(1);

    confirm(paymentId, "CAPTURE", 10_000);

    assertThat(status(paymentId)).isEqualTo("CAPTURED");
    assertThat(journals(paymentId)).isEqualTo(1);
  }

  // --- void ----------------------------------------------------------------------------------

  @Test
  void voidReleasesTheAuthorisationAndBlocksCapture() {
    UUID paymentId = authorised(10_000);

    Response voided = voidPayment(paymentId, "void-1");
    Response captureAfterVoid = capture(paymentId, "cap-1");
    confirm(paymentId, "VOID", 10_000);

    assertThat(voided.status()).isEqualTo(202);
    assertThat(voided.status("status")).isEqualTo("VOID_PENDING");
    assertThat(captureAfterVoid.status()).isEqualTo(409);
    assertThat(status(paymentId)).isEqualTo("VOIDED");
    assertThat(journals(paymentId)).isZero();
  }

  // --- end to end ----------------------------------------------------------------------------

  static Reply accepting(StubPsp.Request request) {
    String requestId = request.body().replaceAll("(?s).*\"psp_request_id\":\"([^\"]+)\".*", "$1");
    return Reply.of(201, StubPsp.operation(requestId, "psp_" + requestId, "PENDING"));
  }

  @Test
  void createAuthoriseCaptureEndToEnd() throws Exception {
    psp.respond(CaptureVoidIntegrationTest::accepting);

    UUID paymentId = create(10_000);
    assertThat(worker.runOnce()).isEqualTo(1);
    confirm(paymentId, "AUTHORIZE", 10_000);
    assertThat(status(paymentId)).isEqualTo("AUTHORIZED");

    assertThat(capture(paymentId, "cap-1").status()).isEqualTo(202);
    assertThat(worker.runOnce()).isEqualTo(1);
    confirm(paymentId, "CAPTURE", 10_000);

    Response payment = exchange(HttpMethod.GET, "/v1/payments/" + paymentId, apiKey, null, null);
    assertThat(payment.status("status")).isEqualTo("CAPTURED");
    assertThat(payment.body().path("capturedMinor").asLong()).isEqualTo(10_000);
    assertThat(journals(paymentId)).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM postings WHERE account_id = ?")
                .param("merchant_payable:" + merchantId)
                .query(Long.class)
                .single())
        .isEqualTo(-9_900);

    String auth = requestId(paymentId, "AUTHORIZE");
    String capture = requestId(paymentId, "CAPTURE");
    assertThat(psp.requestsFor(auth)).hasSize(1);
    JsonNode captureSubmit = json.readTree(psp.requestsFor(capture).getFirst().body());
    assertThat(captureSubmit.path("type").asText()).isEqualTo("CAPTURE");
    assertThat(captureSubmit.path("parent_reference").asText()).isEqualTo("psp_" + auth);
  }
}
