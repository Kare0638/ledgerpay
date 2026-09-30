package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code POST /v1/payments} and {@code GET /v1/payments/{id}} over real HTTP and PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration.class)
class PaymentApiIntegrationTest {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;

  String merchantId;
  String apiKey;

  @BeforeEach
  void newMerchant() {
    merchantId = "m_" + UUID.randomUUID();
    apiKey = createMerchant(merchantId);
  }

  String createMerchant(String id) {
    String key = "mk_test_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(id, ApiKeys.hash(key))
        .update();
    return key;
  }

  static String body(String reference, long amount) {
    return """
        {"merchantReference": "%s", "amountMinor": %d, "currency": "GBP"}"""
        .formatted(reference, amount);
  }

  record Response(int status, JsonNode body, HttpHeaders headers) {
    String code() {
      return body.path("code").asText();
    }
  }

  Response create(String key, String idempotencyKey, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      headers.setBearerAuth(key);
    }
    if (idempotencyKey != null) {
      headers.set("Idempotency-Key", idempotencyKey);
    }
    return exchange(HttpMethod.POST, "/v1/payments", new HttpEntity<>(body, headers));
  }

  Response get(String key, String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(key);
    return exchange(HttpMethod.GET, path, new HttpEntity<>(headers));
  }

  Response exchange(HttpMethod method, String path, HttpEntity<?> entity) {
    ResponseEntity<String> response = rest.exchange(path, method, entity, String.class);
    try {
      JsonNode node =
          response.getBody() == null ? json.nullNode() : json.readTree(response.getBody());
      return new Response(response.getStatusCode().value(), node, response.getHeaders());
    } catch (Exception e) {
      throw new AssertionError("response is not JSON: " + response.getBody(), e);
    }
  }

  static JsonNode withoutTraceId(JsonNode body) {
    ObjectNode copy = body.deepCopy();
    copy.remove("traceId");
    return copy;
  }

  long count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Long.class).single();
  }

  long paymentsFor(String merchant) {
    return count("SELECT count(*) FROM payments WHERE merchant_id = ?", merchant);
  }

  long operationsFor(String merchant) {
    return count(
        """
        SELECT count(*) FROM psp_operations o JOIN payments p ON p.id = o.payment_id
        WHERE p.merchant_id = ?""",
        merchant);
  }

  // --- Creating a payment ------------------------------------------------------------------------

  @Test
  void createsAPaymentWithItsAuthorisationAndEventInOneGo() {
    Response response = create(apiKey, "key-1", body("order-1", 10000));

    assertThat(response.status()).isEqualTo(202);
    UUID paymentId = UUID.fromString(response.body().get("paymentId").asText());
    assertThat(response.body().get("status").asText()).isEqualTo("AUTH_PENDING");
    assertThat(response.body().get("statusUrl").asText()).isEqualTo("/v1/payments/" + paymentId);
    assertThat(response.body().get("traceId").asText())
        .isEqualTo(response.headers().getFirst("X-Trace-Id"));
    assertThat(response.headers().containsKey("Idempotent-Replayed")).isFalse();

    assertThat(
            jdbc.sql(
                    "SELECT status || ' ' || amount_minor || ' ' || currency FROM payments WHERE id = ?")
                .param(paymentId)
                .query(String.class)
                .single())
        .isEqualTo("AUTH_PENDING 10000 GBP");
    assertThat(
            jdbc.sql(
                    """
                    SELECT type || ' ' || status || ' ' || amount_minor || ' ' || (psp_request_id LIKE 'req_%')
                    FROM psp_operations WHERE payment_id = ?""")
                .param(paymentId)
                .query(String.class)
                .list())
        .containsExactly("AUTHORIZE PENDING 10000 true");
    assertThat(
            jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = ?")
                .param(paymentId)
                .query(String.class)
                .list())
        .containsExactly("PaymentCreated");
    assertThat(
            count(
                "SELECT count(*) FROM idempotency_keys WHERE merchant_id = ? AND status = 'COMPLETED'"
                    + " AND response_code = 202 AND response_body -> 'traceId' IS NULL",
                merchantId))
        .isEqualTo(1);
  }

  @Test
  void theMerchantComesFromTheApiKeyNotTheBody() {
    String other = "m_" + UUID.randomUUID();
    createMerchant(other);

    Response response =
        create(
            apiKey,
            "key-1",
            """
            {"merchantId": "%s", "merchantReference": "order-1", "amountMinor": 100, "currency": "GBP"}"""
                .formatted(other));

    assertThat(response.status()).isEqualTo(202);
    assertThat(paymentsFor(merchantId)).isEqualTo(1);
    assertThat(paymentsFor(other)).isZero();
  }

  // --- Request layer: Idempotency-Key ------------------------------------------------------------

  @Test
  void aRetryWithTheSameKeyReplaysTheFirstResponse() {
    Response first = create(apiKey, "key-1", body("order-1", 10000));
    Response retry = create(apiKey, "key-1", body("order-1", 10000));

    assertThat(retry.status()).isEqualTo(202);
    assertThat(withoutTraceId(retry.body())).isEqualTo(withoutTraceId(first.body()));
    assertThat(retry.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
    // The trace ID describes this request, not the one that did the work.
    assertThat(retry.body().get("traceId").asText())
        .isEqualTo(retry.headers().getFirst("X-Trace-Id"))
        .isNotEqualTo(first.body().get("traceId").asText());
    assertThat(paymentsFor(merchantId)).isEqualTo(1);
    assertThat(operationsFor(merchantId)).isEqualTo(1);
  }

  @Test
  void formattingAndFieldOrderDoNotMakeADifferentRequest() {
    create(apiKey, "key-1", body("order-1", 10000));

    Response retry =
        create(
            apiKey,
            "key-1",
            """
            {
              "currency" : "GBP",
              "amountMinor" : 10000,
              "merchantReference" : "order-1"
            }""");

    assertThat(retry.status()).isEqualTo(202);
    assertThat(retry.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
  }

  @Test
  void at02FiftyConcurrentRequestsWithOneKeyCreateOnePayment() throws Exception {
    int threads = 50;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Response>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return create(apiKey, "key-at02", body("order-at02", 10000));
                }));
      }
      ready.await();
      go.countDown();
      List<Response> responses = new ArrayList<>();
      for (Future<Response> future : futures) {
        responses.add(future.get());
      }

      assertThat(responses).extracting(Response::status).containsOnly(202);
      Set<String> paymentIds =
          responses.stream()
              .map(r -> r.body().get("paymentId").asText())
              .collect(Collectors.toSet());
      assertThat(paymentIds).hasSize(1);
      assertThat(responses)
          .filteredOn(r -> r.headers().containsKey("Idempotent-Replayed"))
          .hasSize(threads - 1);
    } finally {
      pool.shutdownNow();
    }

    assertThat(paymentsFor(merchantId)).isEqualTo(1);
    assertThat(operationsFor(merchantId)).isEqualTo(1);
    assertThat(
            count(
                """
                SELECT count(*) FROM outbox_events e JOIN payments p ON p.id = e.aggregate_id
                WHERE p.merchant_id = ?""",
                merchantId))
        .isEqualTo(1);
  }

  @Test
  void at03TheSameKeyWithADifferentAmountIs422AndChangesNothing() {
    Response first = create(apiKey, "key-1", body("order-1", 10000));

    Response changed = create(apiKey, "key-1", body("order-1", 12000));

    assertThat(changed.status()).isEqualTo(422);
    assertThat(changed.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(changed.body().get("traceId").asText()).isNotBlank();
    assertThat(paymentsFor(merchantId)).isEqualTo(1);
    assertThat(operationsFor(merchantId)).isEqualTo(1);
    assertThat(
            get(apiKey, first.body().get("statusUrl").asText()).body().get("amountMinor").asLong())
        .isEqualTo(10000);
  }

  @Test
  void keysAreScopedToTheMerchant() {
    String otherKey = createMerchant("m_" + UUID.randomUUID());

    Response mine = create(apiKey, "shared-key", body("order-1", 10000));
    Response theirs = create(otherKey, "shared-key", body("order-1", 10000));

    assertThat(theirs.status()).isEqualTo(202);
    assertThat(theirs.headers().containsKey("Idempotent-Replayed")).isFalse();
    assertThat(theirs.body().get("paymentId")).isNotEqualTo(mine.body().get("paymentId"));
  }

  // --- Business layer: merchantReference ---------------------------------------------------------

  @Test
  void at04ANewKeyWithTheSameReferenceAndParametersReturnsTheExistingPayment() {
    Response first = create(apiKey, "key-1", body("order-1", 10000));

    Response again = create(apiKey, "key-2", body("order-1", 10000));

    assertThat(again.status()).isEqualTo(202);
    assertThat(again.body().get("paymentId")).isEqualTo(first.body().get("paymentId"));
    assertThat(paymentsFor(merchantId)).isEqualTo(1);
    assertThat(operationsFor(merchantId)).isEqualTo(1);
  }

  @Test
  void at04ANewKeyWithTheSameReferenceButDifferentParametersIs409() {
    create(apiKey, "key-1", body("order-1", 10000));

    Response conflict = create(apiKey, "key-2", body("order-1", 12000));

    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.code()).isEqualTo("DUPLICATE_REFERENCE");
    assertThat(paymentsFor(merchantId)).isEqualTo(1);
    // The rejected request rolled back, so key-2 was not consumed and can be used for a new order.
    assertThat(create(apiKey, "key-2", body("order-2", 12000)).status()).isEqualTo(202);
  }

  // --- Validation and authentication -------------------------------------------------------------

  @Test
  void invalidBodiesAre400WithFieldErrorsAndDoNotConsumeTheKey() {
    Response invalid =
        create(
            apiKey,
            "key-1",
            """
            {"merchantReference": "", "amountMinor": 0, "currency": "EUR"}""");

    assertThat(invalid.status()).isEqualTo(400);
    assertThat(invalid.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(invalid.body().get("fieldErrors").findValuesAsText("field"))
        .containsExactlyInAnyOrder("merchantReference", "amountMinor", "currency");
    assertThat(create(apiKey, "key-1", body("order-1", 100)).status()).isEqualTo(202);
  }

  @Test
  void amountsOutsideOnePennyToOneMillionPoundsAreRejected() {
    assertThat(create(apiKey, "key-1", body("order-1", 100_000_001)).status()).isEqualTo(400);
    assertThat(create(apiKey, "key-2", body("order-2", -1)).status()).isEqualTo(400);
    assertThat(create(apiKey, "key-3", body("order-3", 100_000_000)).status()).isEqualTo(202);
    assertThat(create(apiKey, "key-4", body("order-4", 1)).status()).isEqualTo(202);
  }

  @Test
  void malformedJsonIs400() {
    Response response = create(apiKey, "key-1", "{not json");

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.code()).isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void theIdempotencyKeyIsRequired() {
    Response missing = create(apiKey, null, body("order-1", 100));
    Response blank = create(apiKey, " ", body("order-1", 100));
    Response tooLong = create(apiKey, "k".repeat(256), body("order-1", 100));

    assertThat(List.of(missing, blank, tooLong))
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(400);
              assertThat(r.code()).isEqualTo("VALIDATION_ERROR");
            });
    assertThat(paymentsFor(merchantId)).isZero();
  }

  @Test
  void requestsWithoutAValidApiKeyAre401() {
    for (String key : new String[] {null, "mk_test_unknown"}) {
      Response response = create(key, "key-1", body("order-1", 100));

      assertThat(response.status()).isEqualTo(401);
      assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
      assertThat(response.body().get("traceId").asText()).isNotBlank();
    }
    assertThat(paymentsFor(merchantId)).isZero();
  }

  @Test
  void springMvcErrorsCarryACodeAndTraceIdToo() {
    HttpHeaders auth = new HttpHeaders();
    auth.setBearerAuth(apiKey);
    auth.set("Idempotency-Key", "key-1");

    Response wrongMethod = exchange(HttpMethod.DELETE, "/v1/payments", new HttpEntity<>(auth));
    HttpHeaders textBody = new HttpHeaders(auth);
    textBody.setContentType(MediaType.TEXT_PLAIN);
    Response wrongMediaType =
        exchange(HttpMethod.POST, "/v1/payments", new HttpEntity<>("order-1", textBody));

    assertThat(wrongMethod.status()).isEqualTo(405);
    assertThat(wrongMethod.code()).isEqualTo("METHOD_NOT_ALLOWED");
    assertThat(wrongMediaType.status()).isEqualTo(415);
    assertThat(wrongMediaType.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(List.of(wrongMethod, wrongMediaType))
        .allSatisfy(r -> assertThat(r.body().get("traceId").asText()).isNotBlank());
  }

  @Test
  void theBearerSchemeIsCaseInsensitive() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "bearer " + apiKey);
    headers.set("Idempotency-Key", "key-1");

    Response response =
        exchange(HttpMethod.POST, "/v1/payments", new HttpEntity<>(body("order-1", 100), headers));

    assertThat(response.status()).isEqualTo(202);
  }

  // --- Reading a payment -------------------------------------------------------------------------

  @Test
  void theStatusUrlReturnsThePayment() {
    Response created = create(apiKey, "key-1", body("order-1", 10000));

    Response payment = get(apiKey, created.body().get("statusUrl").asText());

    assertThat(payment.status()).isEqualTo(200);
    assertThat(payment.body().get("paymentId")).isEqualTo(created.body().get("paymentId"));
    assertThat(payment.body().get("status").asText()).isEqualTo("AUTH_PENDING");
    assertThat(payment.body().get("amountMinor").asLong()).isEqualTo(10000);
    assertThat(payment.body().get("capturedMinor").asLong()).isZero();
    assertThat(payment.body().get("refundableMinor").asLong()).isZero();
    assertThat(payment.body().get("refundSummary").asText()).isEqualTo("NONE");
  }

  @Test
  void anotherMerchantsPaymentIs404LikeAMissingOne() {
    Response created = create(apiKey, "key-1", body("order-1", 10000));
    String otherKey = createMerchant("m_" + UUID.randomUUID());

    Response theirs = get(otherKey, created.body().get("statusUrl").asText());
    Response missing = get(apiKey, "/v1/payments/" + UUID.randomUUID());

    assertThat(List.of(theirs, missing))
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(404);
              assertThat(r.code()).isEqualTo("RESOURCE_NOT_FOUND");
            });
  }
}
