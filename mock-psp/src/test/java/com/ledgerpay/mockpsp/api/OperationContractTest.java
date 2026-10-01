package com.ledgerpay.mockpsp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.webhook.WebhookSignature;
import com.ledgerpay.mockpsp.PostgresTestcontainersConfiguration;
import com.ledgerpay.mockpsp.WebhookReceiver;
import com.ledgerpay.mockpsp.operation.Settler;
import com.ledgerpay.mockpsp.webhook.WebhookDispatcher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The contract payment-service relies on: submit, repeat submit, inquiry and signed webhooks, over
 * real HTTP and PostgreSQL. Scheduling is off, so each test settles and dispatches explicitly.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"mockpsp.scheduling.enabled=false", "mockpsp.settle-delay=0s"})
@Import(PostgresTestcontainersConfiguration.class)
class OperationContractTest {

  static final String SECRET = "contract-test-secret";
  static final WebhookReceiver receiver = new WebhookReceiver();

  @DynamicPropertySource
  static void webhookTarget(DynamicPropertyRegistry registry) {
    registry.add("mockpsp.webhook.url", receiver::url);
    registry.add("mockpsp.webhook.secret", () -> SECRET);
  }

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired Settler settler;
  @Autowired WebhookDispatcher dispatcher;
  @Autowired TransactionTemplate transactions;

  String merchant;

  @BeforeEach
  void newMerchant() {
    merchant = "m_" + UUID.randomUUID();
  }

  record Response(int status, JsonNode body, HttpHeaders headers) {
    String field(String name) {
      return body.path(name).asText(null);
    }
  }

  static String newRequestId() {
    return "req_" + UUID.randomUUID();
  }

  String body(String requestId, String type, String parent, long amount) {
    return """
        {"psp_request_id": "%s", "merchant_id": "%s", "type": "%s", "parent_reference": %s,
         "amount_minor": %d, "currency": "GBP"}"""
        .formatted(
            requestId, merchant, type, parent == null ? "null" : "\"" + parent + "\"", amount);
  }

  Response submit(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    var response =
        rest.postForEntity("/v1/operations", new HttpEntity<>(body, headers), String.class);
    return new Response(
        response.getStatusCode().value(), parse(response.getBody()), response.getHeaders());
  }

  Response submit(String requestId, String type, String parent, long amount) {
    return submit(body(requestId, type, parent, amount));
  }

  Response inquire(String requestId) {
    var response = rest.getForEntity("/v1/operations/" + requestId, String.class);
    return new Response(
        response.getStatusCode().value(), parse(response.getBody()), response.getHeaders());
  }

  JsonNode parse(String body) {
    try {
      return json.readTree(body == null ? "{}" : body);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  /** Settles and dispatches until nothing is due, as the scheduled jobs would. */
  void runJobs() {
    while (settler.settleDue() > 0) {}
    while (dispatcher.dispatchDue() > 0) {}
  }

  /** An authorisation that has succeeded, returning its psp_reference. */
  String authorised(long amount) {
    String reference = submit(newRequestId(), "AUTHORIZE", null, amount).field("psp_reference");
    runJobs();
    return reference;
  }

  String captured(long amount) {
    String capture =
        submit(newRequestId(), "CAPTURE", authorised(amount), amount).field("psp_reference");
    runJobs();
    return capture;
  }

  long count(String table, String column, String value) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")
        .param(value)
        .query(Long.class)
        .single();
  }

  // --- submit ------------------------------------------------------------------------------

  @Test
  void newOperationIsAcceptedAsPending() {
    String requestId = newRequestId();

    Response response = submit(requestId, "AUTHORIZE", null, 10_000);

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.headers().getLocation()).hasToString("/v1/operations/" + requestId);
    assertThat(response.field("psp_request_id")).isEqualTo(requestId);
    assertThat(response.field("psp_reference")).startsWith("psp_");
    assertThat(response.field("merchant_id")).isEqualTo(merchant);
    assertThat(response.field("type")).isEqualTo("AUTHORIZE");
    assertThat(response.body().path("amount_minor").asLong()).isEqualTo(10_000);
    assertThat(response.field("currency")).isEqualTo("GBP");
    assertThat(response.field("status")).isEqualTo("PENDING");
    assertThat(response.body().path("resource_version").asInt()).isEqualTo(1);
    assertThat(response.body().path("succeeded_at").isNull()).isTrue();
  }

  @Test
  void repeatSubmitReturnsTheSameOperationInItsCurrentState() {
    String requestId = newRequestId();
    Response first = submit(requestId, "AUTHORIZE", null, 10_000);

    Response pending = submit(requestId, "AUTHORIZE", null, 10_000);
    runJobs();
    Response settled = submit(requestId, "AUTHORIZE", null, 10_000);

    assertThat(pending.status()).isEqualTo(200);
    assertThat(pending.field("psp_reference")).isEqualTo(first.field("psp_reference"));
    assertThat(pending.field("status")).isEqualTo("PENDING");
    assertThat(settled.status()).isEqualTo(200);
    assertThat(settled.field("psp_reference")).isEqualTo(first.field("psp_reference"));
    assertThat(settled.field("status")).isEqualTo("SUCCEEDED");
    assertThat(count("operations", "psp_request_id", requestId)).isEqualTo(1);
    // A repeat is not a new outcome, so it sends no extra webhook.
    assertThat(receiver.deliveriesMentioning(requestId)).hasSize(1);
  }

  @Test
  void sameRequestIdWithDifferentParametersIsRejected() {
    String requestId = newRequestId();
    submit(requestId, "AUTHORIZE", null, 10_000);

    Response differentAmount = submit(requestId, "AUTHORIZE", null, 10_001);
    merchant = "m_" + UUID.randomUUID();
    Response differentMerchant = submit(requestId, "AUTHORIZE", null, 10_000);

    assertThat(differentAmount.status()).isEqualTo(409);
    assertThat(differentAmount.field("code")).isEqualTo("REQUEST_ID_CONFLICT");
    assertThat(differentMerchant.status()).isEqualTo(409);
    assertThat(count("operations", "psp_request_id", requestId)).isEqualTo(1);
    assertThat(inquire(requestId).body().path("amount_minor").asLong()).isEqualTo(10_000);
  }

  @Test
  void concurrentSubmitsWithOneRequestIdRecordOneOperation() throws Exception {
    String requestId = newRequestId();
    String body = body(requestId, "AUTHORIZE", null, 10_000);
    int threads = 20;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Response>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return submit(body);
                }));
      }
      start.countDown();
      List<Response> responses = new ArrayList<>();
      for (var future : futures) {
        responses.add(future.get());
      }

      assertThat(responses).extracting(Response::status).containsOnly(200, 201);
      assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
      assertThat(responses)
          .extracting(r -> r.field("psp_reference"))
          .containsOnly(responses.getFirst().field("psp_reference"));
    }
    assertThat(count("operations", "psp_request_id", requestId)).isEqualTo(1);
  }

  @Test
  void invalidRequestsAreRejectedAndNothingIsRecorded() {
    String requestId = newRequestId();

    assertThat(submit(requestId, "AUTHORIZE", null, 0).status()).isEqualTo(400);
    assertThat(submit(requestId, "AUTHORIZE", "psp_x", 100).field("code"))
        .isEqualTo("INVALID_REQUEST");
    assertThat(submit(requestId, "CAPTURE", null, 100).field("code")).isEqualTo("INVALID_REQUEST");
    assertThat(submit(requestId, "SETTLE", null, 100).status()).isEqualTo(400);
    assertThat(submit("{not json").status()).isEqualTo(400);
    assertThat(
            submit(
                """
                {"psp_request_id": "%s", "merchant_id": "%s", "type": "AUTHORIZE",
                 "amount_minor": 100, "currency": "gbp"}"""
                    .formatted(requestId, merchant)))
        .extracting(Response::status)
        .isEqualTo(400);
    assertThat(count("operations", "psp_request_id", requestId)).isZero();
  }

  @Test
  void fractionalAmountsAreRejectedNotTruncated() {
    String requestId = newRequestId();

    Response response =
        submit(
            """
            {"psp_request_id": "%s", "merchant_id": "%s", "type": "AUTHORIZE",
             "amount_minor": 1050.75, "currency": "GBP"}"""
                .formatted(requestId, merchant));

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.field("code")).isEqualTo("INVALID_REQUEST");
    assertThat(count("operations", "psp_request_id", requestId)).isZero();
  }

  @Test
  void unknownOrForeignParentIsRejectedAndNothingIsRecorded() {
    String authorisation = authorised(10_000);
    String unknown = newRequestId();
    String foreign = newRequestId();
    String wrongType = newRequestId();

    Response unknownParent = submit(unknown, "CAPTURE", "psp_does_not_exist", 10_000);
    merchant = "m_" + UUID.randomUUID();
    Response otherMerchant = submit(foreign, "CAPTURE", authorisation, 10_000);
    Response refundOfAnAuthorisation = submit(wrongType, "REFUND", authorisation, 10_000);

    for (Response response : List.of(unknownParent, otherMerchant, refundOfAnAuthorisation)) {
      assertThat(response.status()).isEqualTo(422);
      assertThat(response.field("code")).isEqualTo("INVALID_REFERENCE");
    }
    for (String id : List.of(unknown, foreign, wrongType)) {
      assertThat(count("operations", "psp_request_id", id)).isZero();
    }
  }

  // --- declines and follow-up operations -------------------------------------------------------

  @Test
  void captureSucceedsAfterTheAuthorisation() {
    String authorisation = authorised(10_000);
    String requestId = newRequestId();

    Response capture = submit(requestId, "CAPTURE", authorisation, 10_000);
    runJobs();

    assertThat(capture.status()).isEqualTo(201);
    assertThat(capture.field("parent_reference")).isEqualTo(authorisation);
    assertThat(inquire(requestId).field("status")).isEqualTo("SUCCEEDED");
  }

  @Test
  void captureBeforeTheAuthorisationSucceedsIsDeclinedAtOnce() {
    String authorisation = submit(newRequestId(), "AUTHORIZE", null, 10_000).field("psp_reference");
    String requestId = newRequestId();

    Response capture = submit(requestId, "CAPTURE", authorisation, 10_000);
    runJobs();

    assertThat(capture.status()).isEqualTo(201);
    assertThat(capture.field("status")).isEqualTo("FAILED");
    assertThat(capture.field("failure_reason")).isEqualTo("PARENT_NOT_SUCCEEDED");
    List<WebhookReceiver.Delivery> webhooks = receiver.deliveriesMentioning(requestId);
    assertThat(webhooks).hasSize(1);
    assertThat(parse(webhooks.getFirst().text()).path("status").asText()).isEqualTo("FAILED");
  }

  @Test
  void captureMustBeForTheFullAuthorisedAmount() {
    String authorisation = authorised(10_000);

    Response partial = submit(newRequestId(), "CAPTURE", authorisation, 9_999);

    assertThat(partial.field("status")).isEqualTo("FAILED");
    assertThat(partial.field("failure_reason")).isEqualTo("AMOUNT_MISMATCH");
  }

  @Test
  void anAuthorisationIsCapturedOrVoidedOnlyOnce() {
    String authorisation = authorised(10_000);
    submit(newRequestId(), "CAPTURE", authorisation, 10_000);

    Response secondCapture = submit(newRequestId(), "CAPTURE", authorisation, 10_000);
    runJobs();
    Response voidAfterCapture = submit(newRequestId(), "VOID", authorisation, 10_000);

    assertThat(secondCapture.field("failure_reason")).isEqualTo("AUTHORIZATION_ALREADY_USED");
    assertThat(voidAfterCapture.field("failure_reason")).isEqualTo("AUTHORIZATION_ALREADY_USED");
  }

  @Test
  void aDeclinedCaptureLeavesTheAuthorisationFreeToVoid() {
    String authorisation = authorised(10_000);
    submit(newRequestId(), "CAPTURE", authorisation, 1); // declined: wrong amount

    Response voided = submit(newRequestId(), "VOID", authorisation, 10_000);

    assertThat(voided.field("status")).isEqualTo("PENDING");
  }

  @Test
  void concurrentCapturesOfOneAuthorisationAcceptOnlyOne() throws Exception {
    String authorisation = authorised(10_000);
    int threads = 10;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Response>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        String body = body(newRequestId(), "CAPTURE", authorisation, 10_000);
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return submit(body);
                }));
      }
      start.countDown();
      List<String> statuses = new ArrayList<>();
      for (var future : futures) {
        statuses.add(future.get().field("status"));
      }

      assertThat(statuses).containsOnlyOnce("PENDING");
      assertThat(statuses).filteredOn("FAILED"::equals).hasSize(threads - 1);
    }
  }

  @Test
  void decisionsAboutOneParentWaitForItsLock() throws Exception {
    // Concurrent submits rarely overlap in the few milliseconds between check and insert, so
    // hold the parent's row lock and show that a capture waits for it instead of deciding.
    String authorisation = authorised(10_000);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  transactions.executeWithoutResult(
                      status -> {
                        jdbc.sql("SELECT 1 FROM operations WHERE psp_reference = ? FOR UPDATE")
                            .param(authorisation)
                            .query()
                            .singleRow();
                        locked.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                      }));
      locked.await();
      String body = body(newRequestId(), "CAPTURE", authorisation, 10_000);
      Future<Response> capture = pool.submit(() -> submit(body));

      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitingForParentLock() > 0 || capture.isDone());
      assertThat(capture.isDone()).as("capture decided without the parent lock").isFalse();

      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
      assertThat(capture.get(10, TimeUnit.SECONDS).field("status")).isEqualTo("PENDING");
    } finally {
      // Release before closing the pool: close() waits for the holder, which waits for this.
      release.countDown();
      pool.close();
    }
  }

  long waitingForParentLock() {
    return jdbc.sql(
            """
            SELECT count(*) FROM pg_stat_activity
             WHERE wait_event_type = 'Lock'
               AND query LIKE '%FROM operations WHERE psp_reference%FOR UPDATE%'
               AND pid <> pg_backend_pid()""")
        .query(Long.class)
        .single();
  }

  @Test
  void refundsMayNotExceedTheCapture() {
    String capture = captured(10_000);

    Response first = submit(newRequestId(), "REFUND", capture, 6_000);
    Response tooMuch = submit(newRequestId(), "REFUND", capture, 4_001);
    Response rest = submit(newRequestId(), "REFUND", capture, 4_000);

    assertThat(first.field("status")).isEqualTo("PENDING");
    assertThat(tooMuch.field("status")).isEqualTo("FAILED");
    assertThat(tooMuch.field("failure_reason")).isEqualTo("REFUND_EXCEEDS_CAPTURE");
    assertThat(rest.field("status")).isEqualTo("PENDING");
  }

  // --- inquiry -----------------------------------------------------------------------------

  @Test
  void inquiryFindsAnOperationByRequestId() {
    String requestId = newRequestId();
    String reference = submit(requestId, "AUTHORIZE", null, 10_000).field("psp_reference");
    runJobs();

    Response found = inquire(requestId);

    assertThat(found.status()).isEqualTo(200);
    assertThat(found.field("psp_reference")).isEqualTo(reference);
    assertThat(found.field("status")).isEqualTo("SUCCEEDED");
    assertThat(found.body().path("resource_version").asInt()).isEqualTo(2);
    assertThat(found.field("succeeded_at")).isNotNull();
  }

  @Test
  void inquiryForAnUnknownRequestIdIsNotFound() {
    // "Not found" is what lets the caller resubmit after a timeout (design §9.1).
    Response missing = inquire(newRequestId());

    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.field("code")).isEqualTo("NOT_FOUND");
  }

  // --- final states --------------------------------------------------------------------------

  @Test
  void finalStatesCannotChange() {
    String requestId = newRequestId();
    String reference = submit(requestId, "AUTHORIZE", null, 10_000).field("psp_reference");
    runJobs();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        UPDATE operations SET status = 'FAILED', succeeded_at = NULL,
                               failure_reason = 'PARENT_NOT_SUCCEEDED'
                         WHERE psp_reference = ?""")
                    .param(reference)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("is final");
    assertThat(settler.settleDue()).isZero();
    assertThat(inquire(requestId).field("status")).isEqualTo("SUCCEEDED");
  }

  @Test
  void databaseAllowsOneActiveCaptureOrVoidPerAuthorisation() {
    String authorisation = authorised(10_000);
    submit(newRequestId(), "CAPTURE", authorisation, 10_000);

    // Bypassing the service, as a bug in the parent lock would.
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO operations
                            (psp_reference, psp_request_id, merchant_id, type, parent_reference,
                             amount_minor, currency, status, settle_at)
                        VALUES (?, ?, ?, 'VOID', ?, 10000, 'GBP', 'PENDING', now())""")
                    .params("psp_" + UUID.randomUUID(), newRequestId(), merchant, authorisation)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_operations_one_capture_or_void");
  }

  // --- webhooks ------------------------------------------------------------------------------

  @Test
  void webhookIsSignedOverTheExactBodyAndReportsTheOutcome() throws Exception {
    String requestId = newRequestId();
    String reference = submit(requestId, "AUTHORIZE", null, 10_000).field("psp_reference");
    runJobs();

    List<WebhookReceiver.Delivery> webhooks = receiver.deliveriesMentioning(requestId);
    assertThat(webhooks).hasSize(1);
    WebhookReceiver.Delivery webhook = webhooks.getFirst();
    assertThat(webhook.contentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(
            WebhookSignature.verify(
                SECRET.getBytes(StandardCharsets.UTF_8),
                webhook.timestamp(),
                webhook.body(),
                webhook.signature(),
                Instant.now()))
        .isTrue();
    assertThat(
            WebhookSignature.verify(
                "wrong-secret".getBytes(StandardCharsets.UTF_8),
                webhook.timestamp(),
                webhook.body(),
                webhook.signature(),
                Instant.now()))
        .isFalse();

    JsonNode event = parse(webhook.text());
    assertThat(event.path("event_id").asText()).startsWith("evt_");
    assertThat(event.path("event_type").asText()).isEqualTo("authorize.succeeded");
    assertThat(event.path("psp_request_id").asText()).isEqualTo(requestId);
    assertThat(event.path("psp_reference").asText()).isEqualTo(reference);
    assertThat(event.path("merchant_id").asText()).isEqualTo(merchant);
    assertThat(event.path("amount_minor").asLong()).isEqualTo(10_000);
    assertThat(event.path("currency").asText()).isEqualTo("GBP");
    assertThat(event.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(event.path("resource_version").asInt()).isEqualTo(2);
    assertThat(Instant.parse(event.path("occurred_at").asText()))
        .isEqualTo(Instant.parse(inquire(requestId).field("succeeded_at")));
  }

  @Test
  void failedDeliveryIsRetriedWithTheSameBodyAndAFreshSignature() {
    String requestId = newRequestId();
    receiver.failWhenBodyContains(requestId);
    submit(requestId, "AUTHORIZE", null, 10_000);
    runJobs();

    String reference = inquire(requestId).field("psp_reference");
    var failed =
        jdbc.sql(
                """
                SELECT attempts, delivered_at IS NULL AS pending, last_error,
                       next_attempt_at >= created_at + interval '1 second' AS backing_off
                  FROM webhook_events WHERE psp_reference = ?""")
            .param(reference)
            .query()
            .singleRow();
    assertThat(failed.get("attempts")).isEqualTo(1);
    assertThat(failed.get("pending")).isEqualTo(true);
    assertThat(failed.get("backing_off")).isEqualTo(true);
    assertThat((String) failed.get("last_error")).contains("500");

    // The receiver recovers and the backoff has elapsed.
    receiver.stopFailing(requestId);
    jdbc.sql("UPDATE webhook_events SET next_attempt_at = now() WHERE psp_reference = ?")
        .param(reference)
        .update();
    runJobs();

    List<WebhookReceiver.Delivery> deliveries = receiver.deliveriesMentioning(requestId);
    assertThat(deliveries).hasSize(2);
    assertThat(deliveries.get(1).body()).isEqualTo(deliveries.get(0).body());
    assertThat(
            WebhookSignature.verify(
                SECRET.getBytes(StandardCharsets.UTF_8),
                deliveries.get(1).timestamp(),
                deliveries.get(1).body(),
                deliveries.get(1).signature(),
                Instant.now()))
        .isTrue();
    assertThat(
            jdbc.sql("SELECT delivered_at IS NOT NULL FROM webhook_events WHERE psp_reference = ?")
                .param(reference)
                .query(Boolean.class)
                .single())
        .isTrue();
  }
}
