package com.ledgerpay.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.common.webhook.WebhookSignature;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Webhooks as the real mock-psp delivers them when it misbehaves (design §9.2–9.3, §12): duplicated
 * (AT-07) and out of order (AT-09). Whatever arrives, the outcome is booked once and a final state
 * never moves.
 */
class WebhookDeliveryAcceptanceTest extends AcceptanceTestSupport {

  /** Captures an authorised payment with {@code fault} on the capture, and waits for CAPTURED. */
  UUID capturedWith(String fault) throws Exception {
    UUID paymentId = authorised();
    LiveStack.FAULTS.inject(merchant, "CAPTURE", fault, 1);
    post("/v1/payments/" + paymentId + "/capture", null);
    until(paymentId, "CAPTURED");
    return paymentId;
  }

  /**
   * Waits until mock-psp has delivered every webhook it queued for this merchant's capture, then
   * applies whatever is still in the inbox. Returns how many distinct events it queued.
   */
  int allCaptureWebhooksDelivered() {
    String undelivered =
        """
        SELECT count(*) FROM webhook_events w JOIN operations o ON o.psp_reference = w.psp_reference
         WHERE o.merchant_id = ? AND o.type = 'CAPTURE' AND w.delivered_at IS NULL""";
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              inbox.processAll();
              return mockPsp.sql(undelivered).param(merchant).query(Long.class).single() == 0;
            });
    inbox.processAll();
    return mockPsp
        .sql(
            """
            SELECT count(*) FROM webhook_events w
              JOIN operations o ON o.psp_reference = w.psp_reference
             WHERE o.merchant_id = ? AND o.type = 'CAPTURE'""")
        .param(merchant)
        .query(Long.class)
        .single()
        .intValue();
  }

  /** Our inbox rows for the payment's capture, oldest first. */
  List<Map<String, Object>> captureEvents(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT i.event_id, i.status, i.error, i.payload->>'status' AS reported
              FROM inbox_events i JOIN psp_operations o ON o.psp_request_id = i.payload->>'psp_request_id'
             WHERE o.payment_id = ? AND o.type = 'CAPTURE'
             ORDER BY i.received_at""")
        .param(paymentId)
        .query()
        .listOfRows();
  }

  List<String> outboxEvents(UUID paymentId) {
    return jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = ?")
        .param(paymentId)
        .query(String.class)
        .list();
  }

  @Test
  void at07DuplicatedWebhooksBookTheCaptureOnce() throws Exception {
    UUID paymentId = capturedWith("DUPLICATE_WEBHOOK");

    // One event ID delivered ten times, and the same outcome under two more event IDs.
    assertThat(allCaptureWebhooksDelivered()).isEqualTo(3);

    List<Map<String, Object>> events = captureEvents(paymentId);
    assertThat(events).as("ten copies of one event are stored once").hasSize(3);
    assertThat(events).allSatisfy(e -> assertThat(e.get("status")).isEqualTo("PROCESSED"));
    assertThat(events)
        .extracting(e -> e.get("error"))
        .as("one applies it, the others find it done")
        .containsOnlyOnce((Object) null)
        .filteredOn(error -> error != null)
        .containsOnly("Already applied");
    assertThat(journals(paymentId)).isEqualTo(1);
    assertThat(capturedMinor(paymentId)).isEqualTo(10_000);
    assertThat(outboxEvents(paymentId)).filteredOn("PaymentCaptured"::equals).hasSize(1);
  }

  @Test
  void at09AStalePendingAndThenAConflictingFailureNeverMoveTheCapture() throws Exception {
    UUID paymentId = capturedWith("OUT_OF_ORDER");

    // The outcome, then the operation's older PENDING event, held back until the outcome landed.
    assertThat(allCaptureWebhooksDelivered()).isEqualTo(2);

    List<Map<String, Object>> events = captureEvents(paymentId);
    assertThat(events).extracting(e -> e.get("reported")).containsExactly("SUCCEEDED", "PENDING");
    Map<String, Object> stale = events.get(1);
    assertThat(stale.get("status")).isEqualTo("PROCESSED");
    assertThat(stale.get("error")).isEqualTo("Stale: PENDING after SUCCEEDED");
    assertThat(status(paymentId)).isEqualTo("CAPTURED");

    // Then the PSP contradicts itself: the same capture, reported FAILED under a new event ID.
    String failed = conflictingFailure((String) events.get(0).get("event_id"));
    assertThat(sendSigned(failed)).isEqualTo(200);
    inbox.processAll();

    Map<String, Object> conflict = captureEvents(paymentId).getLast();
    assertThat(conflict.get("reported")).isEqualTo("FAILED");
    assertThat(conflict.get("status")).isEqualTo("QUARANTINED");
    assertThat((String) conflict.get("error")).contains("SUCCEEDED");
    assertThat(status(paymentId)).isEqualTo("CAPTURED");
    assertThat(capturedMinor(paymentId)).isEqualTo(10_000);
    assertThat(journals(paymentId)).as("no reversing entry").isEqualTo(1);
    assertThat(outboxEvents(paymentId)).doesNotContain("PaymentCaptureFailed");
  }

  /** mock-psp's delivered outcome, turned into a FAILED report under a new event ID. */
  String conflictingFailure(String outcomeEventId) throws Exception {
    String delivered =
        mockPsp
            .sql("SELECT payload FROM webhook_events WHERE event_id = ?")
            .param(outcomeEventId)
            .query(String.class)
            .single();
    ObjectNode event = (ObjectNode) json.readTree(delivered);
    event.put("event_id", "evt_" + UUID.randomUUID());
    event.put("event_type", "capture.failed");
    event.put("status", "FAILED");
    return json.writeValueAsString(event);
  }

  int sendSigned(String body) throws Exception {
    long timestamp = Instant.now().getEpochSecond();
    byte[] raw = body.getBytes(StandardCharsets.UTF_8);
    String signature =
        WebhookSignature.sign(
            LiveStack.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), timestamp, raw);
    var request =
        HttpRequest.newBuilder(URI.create(LiveStack.paymentUrl() + "/webhooks/psp"))
            .header("Content-Type", "application/json")
            .header(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(timestamp))
            .header(WebhookSignature.SIGNATURE_HEADER, signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
