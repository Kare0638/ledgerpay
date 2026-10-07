package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.psp.StubPsp;
import com.ledgerpay.payment.webhook.InboxProcessor;
import com.ledgerpay.payment.webhook.SignedWebhooks;
import com.ledgerpay.payment.webhook.SignedWebhooks.Event;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Who may see what (design §7): a merchant sees only its own payments, refunds and payable, and
 * everything else is the same 404 as something that does not exist (AT-14). Operations endpoints
 * take their own key, which no merchant key can stand in for.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledgerpay.psp.webhook-secret=" + SignedWebhooks.SECRET,
      "ledgerpay.ops.api-key-hash=" + MerchantIsolationIntegrationTest.OPS_KEY_HASH
    })
@Import(PostgresTestcontainersConfiguration.class)
class MerchantIsolationIntegrationTest {

  static final String OPS_KEY = "ops_test_key";
  // ApiKeys.hash(OPS_KEY), upper case: the configured hash is matched case-insensitively.
  static final String OPS_KEY_HASH =
      "17DA52ACDA373B82D53E6601ABFFB233BC9A735579DF11D2C78596CFA5890134";

  static final StubPsp psp = new StubPsp();

  @DynamicPropertySource
  static void pspUrl(DynamicPropertyRegistry registry) {
    registry.add("ledgerpay.psp.base-url", psp::url);
  }

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
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

  record Response(int status, JsonNode body) {
    String code() {
      return body.path("code").asText();
    }

    String field(String name) {
      return body.path(name).asText();
    }
  }

  Response exchange(HttpMethod method, String path, String key, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) {
      headers.setBearerAuth(key);
    }
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    var response = rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    try {
      return new Response(
          response.getStatusCode().value(),
          json.readTree(response.getBody() == null ? "{}" : response.getBody()));
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

  void deliver(Event event) {
    assertThat(SignedWebhooks.send(rest, event.json()).getStatusCode().value()).isEqualTo(200);
    processor.processAll();
  }

  UUID created(long amount) {
    String body =
        """
        {"merchantReference": "order-%s", "amountMinor": %d, "currency": "GBP"}"""
            .formatted(UUID.randomUUID(), amount);
    return UUID.fromString(
        exchange(HttpMethod.POST, "/v1/payments", apiKey, body).field("paymentId"));
  }

  UUID captured(long amount) {
    UUID paymentId = created(amount);
    deliver(
        Event.of("AUTHORIZE", requestId(paymentId, "AUTHORIZE"), merchantId, amount, "SUCCEEDED"));
    exchange(HttpMethod.POST, "/v1/payments/" + paymentId + "/capture", apiKey, null);
    deliver(Event.of("CAPTURE", requestId(paymentId, "CAPTURE"), merchantId, amount, "SUCCEEDED"));
    return paymentId;
  }

  String balancePath(String accountId) {
    return "/v1/accounts/" + accountId + "/balance";
  }

  // --- AT-14 ---------------------------------------------------------------------------------

  /** AT-14: another merchant's payments, refunds and balances are 404, and nothing leaks. */
  @Test
  void at14CrossMerchantReadsAndWritesAre404WithNothingLeaked() {
    UUID paymentId = captured(10_000);
    UUID refundId =
        UUID.fromString(
            exchange(
                    HttpMethod.POST,
                    "/v1/payments/" + paymentId + "/refunds",
                    apiKey,
                    """
                    {"merchantReference": "rf-1", "amountMinor": 1000, "reason": "x"}""")
                .field("refundId"));
    String other = createMerchant("m_other_" + UUID.randomUUID());

    List<Response> foreign =
        List.of(
            exchange(HttpMethod.GET, "/v1/payments/" + paymentId, other, null),
            exchange(HttpMethod.GET, "/v1/refunds/" + refundId, other, null),
            exchange(HttpMethod.GET, balancePath("merchant_payable:" + merchantId), other, null),
            exchange(HttpMethod.POST, "/v1/payments/" + paymentId + "/capture", other, null),
            exchange(HttpMethod.POST, "/v1/payments/" + paymentId + "/void", other, null),
            exchange(
                HttpMethod.POST,
                "/v1/payments/" + paymentId + "/refunds",
                other,
                """
                {"merchantReference": "rf-x", "amountMinor": 1000, "reason": "x"}"""));
    // The same answers for IDs that exist nowhere: existence is not leaked.
    List<Response> missing =
        List.of(
            exchange(HttpMethod.GET, "/v1/payments/" + UUID.randomUUID(), other, null),
            exchange(HttpMethod.GET, "/v1/refunds/" + UUID.randomUUID(), other, null),
            exchange(HttpMethod.GET, balancePath("merchant_payable:m_nobody"), other, null));

    assertThat(foreign)
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(404);
              assertThat(r.code()).isEqualTo("RESOURCE_NOT_FOUND");
              // Only the problem's own fields: no status, amount, balance or reference.
              assertThat(r.body().fieldNames())
                  .toIterable()
                  .containsOnly("type", "title", "status", "detail", "instance", "code", "traceId");
            });
    assertThat(missing)
        .allSatisfy(r -> assertThat(r.status()).isEqualTo(404))
        .extracting(r -> r.body().path("title").asText())
        .containsOnly(foreign.getFirst().body().path("title").asText());
    // Nothing changed for the owner.
    Response own = exchange(HttpMethod.GET, "/v1/payments/" + paymentId, apiKey, null);
    assertThat(own.field("status")).isEqualTo("CAPTURED");
    assertThat(own.body().path("refundReservedMinor").asLong()).isEqualTo(1_000);
    assertThat(
            jdbc.sql("SELECT count(*) FROM refunds WHERE payment_id = ?")
                .param(paymentId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  // --- balances ------------------------------------------------------------------------------

  @Test
  void aMerchantReadsItsOwnPayable() {
    captured(10_000);

    Response balance =
        exchange(HttpMethod.GET, balancePath("merchant_payable:" + merchantId), apiKey, null);

    assertThat(balance.status()).isEqualTo(200);
    assertThat(balance.field("accountId")).isEqualTo("merchant_payable:" + merchantId);
    assertThat(balance.field("currency")).isEqualTo("GBP");
    // Debit positive: £100 captured at 1 % leaves £99 owed to the merchant.
    assertThat(balance.body().path("balanceMinor").asLong()).isEqualTo(-9_900);
  }

  @Test
  void aPayableWithNoPostingsYetIsZero() {
    Response balance =
        exchange(HttpMethod.GET, balancePath("merchant_payable:" + merchantId), apiKey, null);

    assertThat(balance.status()).isEqualTo(200);
    assertThat(balance.body().path("balanceMinor").asLong()).isZero();
  }

  @Test
  void platformAccountsAreNotFoundForAMerchant() {
    captured(10_000);

    List<Response> responses =
        List.of(
            exchange(HttpMethod.GET, balancePath("fee_revenue"), apiKey, null),
            exchange(HttpMethod.GET, balancePath("psp_receivable:mock-psp"), apiKey, null),
            exchange(HttpMethod.GET, balancePath("not-an-account"), apiKey, null));

    assertThat(responses)
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(404);
              assertThat(r.code()).isEqualTo("RESOURCE_NOT_FOUND");
            });
  }

  // --- the ops key ---------------------------------------------------------------------------

  String inquiryPath(UUID operationId) {
    return "/ops/psp-operations/" + operationId + "/inquiry";
  }

  UUID authorisationOf(UUID paymentId) {
    return jdbc.sql("SELECT id FROM psp_operations WHERE payment_id = ? AND type = 'AUTHORIZE'")
        .param(paymentId)
        .query(UUID.class)
        .single();
  }

  @Test
  void opsEndpointsRejectEveryKeyButTheOpsKey() {
    UUID operation = authorisationOf(created(10_000));

    List<Response> rejected =
        List.of(
            exchange(HttpMethod.POST, inquiryPath(operation), null, null),
            exchange(HttpMethod.POST, inquiryPath(operation), apiKey, null),
            exchange(HttpMethod.POST, inquiryPath(operation), "ops_wrong_key", null),
            exchange(HttpMethod.POST, inquiryPath(operation), OPS_KEY_HASH, null));

    assertThat(rejected)
        .allSatisfy(
            r -> {
              assertThat(r.status()).isEqualTo(401);
              assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
            });
    // And the ops key is no merchant key.
    assertThat(
            exchange(HttpMethod.GET, "/v1/payments/" + UUID.randomUUID(), OPS_KEY, null).status())
        .isEqualTo(401);
  }

  @Test
  void anInquiryMakesAnOperationSetAsideForReviewDueAgain() {
    UUID operation = authorisationOf(created(10_000));
    jdbc.sql(
            """
            UPDATE psp_operations
               SET needs_review = true, failures = 10, next_attempt_at = now() + interval '1 day'
             WHERE id = ?""")
        .param(operation)
        .update();

    Response response = exchange(HttpMethod.POST, inquiryPath(operation), OPS_KEY, null);

    assertThat(response.status()).isEqualTo(202);
    assertThat(response.field("pspOperationId")).isEqualTo(operation.toString());
    Map<String, Object> row =
        jdbc.sql(
                """
                SELECT needs_review, failures, next_attempt_at <= now() AS due, status
                  FROM psp_operations WHERE id = ?""")
            .param(operation)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("needs_review", false)
        .containsEntry("failures", 0)
        .containsEntry("due", true)
        .containsEntry("status", "PENDING");
  }

  @Test
  void anInquiryIsRefusedWhileAWorkerHoldsTheLease() {
    UUID operation = authorisationOf(created(10_000));
    // Mid-call: the worker claimed it with 9 failures and would count the 10th from that.
    jdbc.sql(
            """
            UPDATE psp_operations
               SET needs_review = false, failures = 9, lease_until = now() + interval '1 minute',
                   next_attempt_at = now() + interval '1 day'
             WHERE id = ?""")
        .param(operation)
        .update();

    Response refused = exchange(HttpMethod.POST, inquiryPath(operation), OPS_KEY, null);

    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.code()).isEqualTo("INVALID_STATE");
    assertThat(
            jdbc.sql("SELECT failures FROM psp_operations WHERE id = ?")
                .param(operation)
                .query(Integer.class)
                .single())
        .isEqualTo(9);

    // Once the lease has lapsed (the worker died), the operator can go ahead.
    jdbc.sql("UPDATE psp_operations SET lease_until = now() - interval '1 second' WHERE id = ?")
        .param(operation)
        .update();

    assertThat(exchange(HttpMethod.POST, inquiryPath(operation), OPS_KEY, null).status())
        .isEqualTo(202);
  }

  @Test
  void anInquiryOfAFinalOrUnknownOperationIsRefused() {
    UUID paymentId = captured(10_000);

    Response finalOne =
        exchange(HttpMethod.POST, inquiryPath(authorisationOf(paymentId)), OPS_KEY, null);
    Response unknown = exchange(HttpMethod.POST, inquiryPath(UUID.randomUUID()), OPS_KEY, null);

    assertThat(finalOne.status()).isEqualTo(409);
    assertThat(finalOne.code()).isEqualTo("INVALID_STATE");
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.code()).isEqualTo("RESOURCE_NOT_FOUND");
  }
}
