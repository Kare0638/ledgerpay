package com.ledgerpay.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PaymentServiceApplication;
import com.ledgerpay.payment.api.ApiKeys;
import com.ledgerpay.payment.psp.PspOperationWorker;
import com.ledgerpay.payment.webhook.InboxProcessor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Unknown outcomes against the real mock-psp (design §9.1, §12). AT-06: the PSP captures, but its
 * answer never reaches us. The capture must be found by inquiry and booked once, with a single
 * capture at the PSP: a timeout is never a failure, and never a reason to submit again blindly.
 */
@SpringBootTest(
    classes = PaymentServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class UnknownOutcomeAcceptanceTest {

  @DynamicPropertySource
  static void stack(DynamicPropertyRegistry registry) {
    LiveStack.paymentServiceProperties(registry);
  }

  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired PspOperationWorker worker;
  @Autowired InboxProcessor inbox;

  final HttpClient http = HttpClient.newHttpClient();
  JdbcClient mockPsp;
  String merchant;
  String apiKey;

  @BeforeEach
  void merchant() {
    mockPsp =
        JdbcClient.create(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                LiveStack.jdbcUrl("mockpsp"),
                LiveStack.POSTGRES.getUsername(),
                LiveStack.POSTGRES.getPassword()));
    merchant = "m_" + UUID.randomUUID();
    apiKey = "mk_test_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, ApiKeys.hash(apiKey))
        .update();
  }

  JsonNode post(String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(LiveStack.paymentUrl() + path))
            .header("Authorization", "Bearer " + apiKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .POST(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
    return json.readTree(response.body());
  }

  String status(UUID paymentId) {
    return jdbc.sql("SELECT status FROM payments WHERE id = ?")
        .param(paymentId)
        .query(String.class)
        .single();
  }

  /** Runs the worker and the inbox until the payment reaches {@code status}. */
  void until(UUID paymentId, String status) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              worker.drain();
              inbox.processAll();
              return status(paymentId).equals(status);
            });
  }

  UUID authorised() throws Exception {
    JsonNode created =
        post(
            "/v1/payments",
            """
            {"merchantReference": "order-%s", "amountMinor": 10000, "currency": "GBP"}"""
                .formatted(UUID.randomUUID()));
    UUID paymentId = UUID.fromString(created.path("paymentId").asText());
    until(paymentId, "AUTHORIZED");
    return paymentId;
  }

  @Test
  void at06TimeoutAfterCommitOnCaptureIsFoundByInquiryAndBookedOnce() throws Exception {
    UUID paymentId = authorised();
    LiveStack.FAULTS.inject(merchant, "CAPTURE", "TIMEOUT_AFTER_COMMIT", 1);

    post("/v1/payments/" + paymentId + "/capture", null);
    worker.drain(); // the submit reaches mock-psp, which commits it and withholds the answer

    Map<String, Object> afterTimeout =
        jdbc.sql(
                """
                SELECT status, attempts, last_error, psp_reference FROM psp_operations
                 WHERE payment_id = ? AND type = 'CAPTURE'""")
            .param(paymentId)
            .query()
            .singleRow();
    assertThat(afterTimeout.get("status")).isEqualTo("PENDING");
    assertThat(afterTimeout.get("attempts")).isEqualTo(1);
    assertThat(afterTimeout.get("psp_reference")).isNull();
    assertThat((String) afterTimeout.get("last_error")).startsWith("submit:");
    assertThat(status(paymentId)).isEqualTo("CAPTURE_PENDING");

    // The retry is due (skipping the backoff): it inquires, finds the capture and books it. The
    // webhook mock-psp sent meanwhile is processed afterwards and finds the work done.
    jdbc.sql(
            """
            UPDATE psp_operations SET next_attempt_at = now() - interval '1 second'
             WHERE payment_id = ? AND type = 'CAPTURE'""")
        .param(paymentId)
        .update();
    worker.drain();
    assertThat(status(paymentId)).as("booked by the inquiry").isEqualTo("CAPTURED");
    inbox.processAll();

    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM journal_entries j JOIN psp_operations o ON o.id = j.psp_operation_id
                     WHERE o.payment_id = ?""")
                .param(paymentId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT captured_minor FROM payments WHERE id = ?")
                .param(paymentId)
                .query(Long.class)
                .single())
        .isEqualTo(10_000);
    // mock-psp's own records: exactly one capture, succeeded.
    assertThat(
            mockPsp
                .sql("SELECT status FROM operations WHERE merchant_id = ? AND type = 'CAPTURE'")
                .param(merchant)
                .query(String.class)
                .list())
        .containsExactly("SUCCEEDED");
  }
}
