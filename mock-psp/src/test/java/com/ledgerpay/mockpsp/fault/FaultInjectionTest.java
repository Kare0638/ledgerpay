package com.ledgerpay.mockpsp.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.mockpsp.MockPspFaults;
import com.ledgerpay.mockpsp.PostgresTestcontainersConfiguration;
import com.ledgerpay.mockpsp.WebhookReceiver;
import com.ledgerpay.mockpsp.operation.Settler;
import com.ledgerpay.mockpsp.webhook.WebhookDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every fault of design §9.4, injected through {@code /_admin/faults} under the dev profile, over
 * real HTTP and PostgreSQL. Scheduling is off, so each test settles and dispatches explicitly.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mockpsp.scheduling.enabled=false",
      "mockpsp.settle-delay=0s",
      "mockpsp.faults.response-delay=3s"
    })
@ActiveProfiles("dev")
@Import(PostgresTestcontainersConfiguration.class)
class FaultInjectionTest {

  static final WebhookReceiver receiver = new WebhookReceiver();

  @DynamicPropertySource
  static void webhookTarget(DynamicPropertyRegistry registry) {
    registry.add("mockpsp.webhook.url", receiver::url);
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired Settler settler;
  @Autowired WebhookDispatcher dispatcher;

  final HttpClient http = HttpClient.newHttpClient();
  MockPspFaults faults;
  String merchant;

  @BeforeEach
  void setUp() {
    faults = new MockPspFaults("http://localhost:" + port);
    faults.clear();
    merchant = "m_" + UUID.randomUUID();
  }

  String body(String requestId, String type, String parent, long amount) {
    return """
        {"psp_request_id": "%s", "merchant_id": "%s", "type": "%s", "parent_reference": %s,
         "amount_minor": %d, "currency": "GBP"}"""
        .formatted(
            requestId, merchant, type, parent == null ? "null" : "\"" + parent + "\"", amount);
  }

  HttpResponse<String> submit(String body, Duration timeout) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/operations"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode submit(String requestId, String type) throws Exception {
    return json.readTree(
        submit(body(requestId, type, null, 10_000), Duration.ofSeconds(10)).body());
  }

  JsonNode inquire(String requestId) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v1/operations/" + requestId))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return json.readTree(response.body());
  }

  void runJobs() {
    while (settler.settleDue() > 0) {}
    while (dispatcher.dispatchDue() > 0) {}
  }

  static String newRequestId() {
    return "req_" + UUID.randomUUID();
  }

  List<JsonNode> webhooks(String requestId) {
    return receiver.deliveriesMentioning(requestId).stream()
        .map(
            delivery -> {
              try {
                return json.readTree(delivery.text());
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            })
        .toList();
  }

  void makeWebhooksDue(String requestId) throws Exception {
    jdbc.sql(
            """
            UPDATE webhook_events SET next_attempt_at = now()
             WHERE psp_reference = (SELECT psp_reference FROM operations WHERE psp_request_id = ?)""")
        .param(requestId)
        .update();
  }

  @Test
  void declineFailsTheOperationAtOnceAndOnlyAsOftenAsAsked() throws Exception {
    faults.inject(merchant, "AUTHORIZE", "DECLINE", 1);
    String declined = newRequestId();
    String accepted = newRequestId();

    JsonNode first = submit(declined, "AUTHORIZE");
    JsonNode second = submit(accepted, "AUTHORIZE");
    runJobs();

    assertThat(first.path("status").asText()).isEqualTo("FAILED");
    assertThat(first.path("failure_reason").asText()).isEqualTo("DECLINED");
    assertThat(second.path("status").asText()).isEqualTo("PENDING");
    assertThat(webhooks(declined))
        .singleElement()
        .satisfies(event -> assertThat(event.path("status").asText()).isEqualTo("FAILED"));
    assertThat(inquire(accepted).path("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void aRuleAppliesOnlyToItsMerchantAndType() throws Exception {
    faults.inject(merchant, "CAPTURE", "DECLINE");
    String authorisation = newRequestId();

    JsonNode sameMerchantOtherType = submit(authorisation, "AUTHORIZE");
    merchant = "m_" + UUID.randomUUID();
    JsonNode otherMerchant = submit(newRequestId(), "AUTHORIZE");

    assertThat(sameMerchantOtherType.path("status").asText()).isEqualTo("PENDING");
    assertThat(otherMerchant.path("status").asText()).isEqualTo("PENDING");
    // The response never reveals that faults exist.
    assertThat(sameMerchantOtherType.has("fault")).isFalse();
  }

  @Test
  void timeoutAfterCommitLosesTheAnswerButNotTheOperation() throws Exception {
    faults.inject(merchant, "AUTHORIZE", "TIMEOUT_AFTER_COMMIT", 1);
    String requestId = newRequestId();
    String body = body(requestId, "AUTHORIZE", null, 10_000);

    assertThatThrownBy(() -> submit(body, Duration.ofMillis(500)))
        .isInstanceOf(HttpTimeoutException.class);

    // The operation was committed before the answer was held back: an inquiry finds it.
    assertThat(inquire(requestId).path("status").asText()).isEqualTo("PENDING");
    runJobs();
    assertThat(inquire(requestId).path("status").asText()).isEqualTo("SUCCEEDED");
    // Resubmitting the same request ID is a repeat: answered at once, nothing new recorded.
    long started = System.nanoTime();
    var repeat = submit(body, Duration.ofSeconds(10));
    assertThat(repeat.statusCode()).isEqualTo(200);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(
            jdbc.sql("SELECT count(*) FROM operations WHERE psp_request_id = ?")
                .param(requestId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void dropWebhookSettlesButNeverReportsIt() throws Exception {
    faults.inject(merchant, "AUTHORIZE", "DROP_WEBHOOK");
    String requestId = newRequestId();

    submit(requestId, "AUTHORIZE");
    runJobs();

    assertThat(inquire(requestId).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(webhooks(requestId)).isEmpty();
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM webhook_events e JOIN operations o USING (psp_reference)
                     WHERE o.psp_request_id = ?""")
                .param(requestId)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void duplicateWebhookSendsOneEventTenTimesAndTheOutcomeUnderTwoMoreIds() throws Exception {
    faults.inject(merchant, "AUTHORIZE", "DUPLICATE_WEBHOOK");
    String requestId = newRequestId();

    submit(requestId, "AUTHORIZE");
    runJobs();

    List<JsonNode> events = webhooks(requestId);
    assertThat(events).hasSize(12);
    assertThat(events)
        .allSatisfy(e -> assertThat(e.path("status").asText()).isEqualTo("SUCCEEDED"));
    var byEventId =
        events.stream()
            .collect(
                Collectors.groupingBy(e -> e.path("event_id").asText(), Collectors.counting()));
    assertThat(byEventId.values()).containsExactlyInAnyOrder(10L, 1L, 1L);
  }

  @Test
  void outOfOrderSendsAnOlderPendingEventAfterTheOutcome() throws Exception {
    faults.inject(merchant, "AUTHORIZE", "OUT_OF_ORDER");
    String requestId = newRequestId();
    submit(requestId, "AUTHORIZE");
    runJobs();

    makeWebhooksDue(requestId);
    runJobs();

    List<JsonNode> events = webhooks(requestId);
    assertThat(events).hasSize(2);
    JsonNode outcome = events.get(0);
    JsonNode stale = events.get(1);
    assertThat(outcome.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(stale.path("status").asText()).isEqualTo("PENDING");
    assertThat(stale.path("event_type").asText()).isEqualTo("authorize.pending");
    assertThat(stale.path("resource_version").asInt())
        .isLessThan(outcome.path("resource_version").asInt());
    assertThat(stale.path("event_id").asText()).isNotEqualTo(outcome.path("event_id").asText());
  }

  @Test
  void outOfOrderHoldsTheStaleEventBackUntilTheOutcomeIsDelivered() throws Exception {
    faults.inject(merchant, "AUTHORIZE", "OUT_OF_ORDER");
    String requestId = newRequestId();
    receiver.failWhenBodyContains("authorize.succeeded");
    try {
      submit(requestId, "AUTHORIZE");
      runJobs();
      // Both are due now and the outcome fails again: retry backoff alone would let the stale
      // event overtake it.
      makeWebhooksDue(requestId);
      runJobs();

      assertThat(webhooks(requestId))
          .isNotEmpty()
          .allSatisfy(event -> assertThat(event.path("status").asText()).isEqualTo("SUCCEEDED"));
    } finally {
      receiver.stopFailing("authorize.succeeded");
    }

    makeWebhooksDue(requestId);
    runJobs();

    List<JsonNode> events = webhooks(requestId);
    assertThat(events.getLast().path("status").asText()).isEqualTo("PENDING");
    assertThat(events.subList(0, events.size() - 1))
        .allSatisfy(event -> assertThat(event.path("status").asText()).isEqualTo("SUCCEEDED"));
  }

  @Test
  void outOfOrderSendsNoStaleEventForAnOperationThatFailedWhenSubmitted() throws Exception {
    faults.inject(merchant, "CAPTURE", "OUT_OF_ORDER");
    String authorisation = submit(newRequestId(), "AUTHORIZE").path("psp_reference").asText();
    String capture = newRequestId();

    // The authorisation has not settled yet, so the capture is declined at once at version 1:
    // there is no older version to send.
    JsonNode declined =
        json.readTree(
            submit(body(capture, "CAPTURE", authorisation, 10_000), Duration.ofSeconds(10)).body());
    runJobs();
    makeWebhooksDue(capture);
    runJobs();

    assertThat(declined.path("failure_reason").asText()).isEqualTo("PARENT_NOT_SUCCEEDED");
    assertThat(webhooks(capture))
        .singleElement()
        .satisfies(event -> assertThat(event.path("status").asText()).isEqualTo("FAILED"));
  }

  @Test
  void delayWebhookSendsTheOutcomeLate() throws Exception {
    faults.inject(merchant, "AUTHORIZE", "DELAY_WEBHOOK");
    String requestId = newRequestId();
    submit(requestId, "AUTHORIZE");
    runJobs();

    assertThat(inquire(requestId).path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(webhooks(requestId)).isEmpty();
    assertThat(
            jdbc.sql(
                    """
                    SELECT e.next_attempt_at >= e.created_at + interval '25 seconds'
                      FROM webhook_events e JOIN operations o USING (psp_reference)
                     WHERE o.psp_request_id = ?""")
                .param(requestId)
                .query(Boolean.class)
                .single())
        .isTrue();

    makeWebhooksDue(requestId);
    runJobs();

    assertThat(webhooks(requestId))
        .singleElement()
        .satisfies(e -> assertThat(e.path("status").asText()).isEqualTo("SUCCEEDED"));
  }

  @Test
  void rulesCanBeListedRemovedAndCleared() throws Exception {
    faults.inject(merchant, null, "DROP_WEBHOOK");
    var base = URI.create("http://localhost:" + port + "/_admin/faults");

    JsonNode rules =
        json.readTree(
            http.send(HttpRequest.newBuilder(base).build(), HttpResponse.BodyHandlers.ofString())
                .body());
    assertThat(rules).hasSize(1);
    String id = rules.get(0).path("id").asText();
    var removed =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/" + id)).DELETE().build(),
            HttpResponse.BodyHandlers.discarding());
    var invalid =
        http.send(
            HttpRequest.newBuilder(base)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"merchant_id\": \"m\", \"fault\": \"EXPLODE\"}"))
                .build(),
            HttpResponse.BodyHandlers.discarding());

    assertThat(removed.statusCode()).isEqualTo(204);
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(submit(newRequestId(), "AUTHORIZE").path("status").asText()).isEqualTo("PENDING");
  }
}
