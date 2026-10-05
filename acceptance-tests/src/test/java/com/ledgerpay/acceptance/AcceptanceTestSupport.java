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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What every acceptance test shares: payment-service on {@link LiveStack}, a fresh merchant per
 * test, its API, and read access to mock-psp's own database. The worker and the inbox are driven by
 * hand, so each test decides when calls are made and webhooks applied.
 */
@SpringBootTest(
    classes = PaymentServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
abstract class AcceptanceTestSupport {

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
            new DriverManagerDataSource(
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

  long journals(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM journal_entries j JOIN psp_operations o ON o.id = j.psp_operation_id
             WHERE o.payment_id = ?""")
        .param(paymentId)
        .query(Long.class)
        .single();
  }

  long capturedMinor(UUID paymentId) {
    return jdbc.sql("SELECT captured_minor FROM payments WHERE id = ?")
        .param(paymentId)
        .query(Long.class)
        .single();
  }
}
