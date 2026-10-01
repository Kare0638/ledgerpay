package com.ledgerpay.mockpsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** With the real scheduler: an accepted operation succeeds after the delay and is reported. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"mockpsp.settle-delay=100ms", "mockpsp.poll-interval=50ms"})
@Import(PostgresTestcontainersConfiguration.class)
class ScheduledFlowTest {

  static final WebhookReceiver receiver = new WebhookReceiver();

  @DynamicPropertySource
  static void webhookTarget(DynamicPropertyRegistry registry) {
    registry.add("mockpsp.webhook.url", receiver::url);
  }

  @Autowired TestRestTemplate rest;

  @Test
  void acceptedOperationSucceedsAndIsReportedByWebhook() {
    String requestId = "req_" + UUID.randomUUID();
    var body =
        Map.of(
            "psp_request_id", requestId,
            "merchant_id", "m_scheduled",
            "type", "AUTHORIZE",
            "amount_minor", 2_500,
            "currency", "GBP");

    var submitted = rest.postForEntity("/v1/operations", body, Map.class);

    assertThat(submitted.getStatusCode().value()).isEqualTo(201);
    assertThat(submitted.getBody()).containsEntry("status", "PENDING");
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(receiver.deliveriesMentioning(requestId)).hasSize(1));
    assertThat(receiver.deliveriesMentioning(requestId).getFirst().text())
        .contains("\"status\":\"SUCCEEDED\"");
    assertThat(rest.getForEntity("/v1/operations/" + requestId, Map.class).getBody())
        .containsEntry("status", "SUCCEEDED");
  }
}
