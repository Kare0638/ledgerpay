package com.ledgerpay.payment.webhook;

import com.ledgerpay.common.webhook.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds and sends webhooks the way mock-psp does (design §9.2, §9.4). */
public final class SignedWebhooks {

  public static final String SECRET = "test-webhook-secret";

  public record Event(
      String eventId,
      String type,
      String pspRequestId,
      String pspReference,
      String merchantId,
      long amountMinor,
      String status,
      int resourceVersion) {

    public static Event of(
        String type, String pspRequestId, String merchantId, long amountMinor, String status) {
      return new Event(
          "evt_" + UUID.randomUUID(),
          type,
          pspRequestId,
          "psp_" + pspRequestId,
          merchantId,
          amountMinor,
          status,
          2);
    }

    public Event withEventId(String id) {
      return new Event(
          id, type, pspRequestId, pspReference, merchantId, amountMinor, status, resourceVersion);
    }

    public Event withStatus(String newStatus) {
      return new Event(
          eventId,
          type,
          pspRequestId,
          pspReference,
          merchantId,
          amountMinor,
          newStatus,
          resourceVersion);
    }

    public Event withAmount(long amount) {
      return new Event(
          eventId, type, pspRequestId, pspReference, merchantId, amount, status, resourceVersion);
    }

    public Event withMerchant(String merchant) {
      return new Event(
          eventId,
          type,
          pspRequestId,
          pspReference,
          merchant,
          amountMinor,
          status,
          resourceVersion);
    }

    public Event withResourceVersion(int version) {
      return new Event(
          eventId, type, pspRequestId, pspReference, merchantId, amountMinor, status, version);
    }

    public String json() {
      return """
          {"event_id":"%s","event_type":"%s.%s","psp_request_id":"%s","psp_reference":"%s",\
          "merchant_id":"%s","amount_minor":%d,"currency":"GBP","status":"%s",\
          "occurred_at":"2026-10-01T09:00:00Z","resource_version":%d}"""
          .formatted(
              eventId,
              type.toLowerCase(),
              status.toLowerCase(),
              pspRequestId,
              pspReference,
              merchantId,
              amountMinor,
              status,
              resourceVersion);
    }
  }

  private SignedWebhooks() {}

  public static ResponseEntity<String> send(TestRestTemplate rest, String body) {
    long timestamp = Instant.now().getEpochSecond();
    String signature =
        WebhookSignature.sign(
            SECRET.getBytes(StandardCharsets.UTF_8),
            timestamp,
            body.getBytes(StandardCharsets.UTF_8));
    return send(rest, body, String.valueOf(timestamp), signature);
  }

  public static ResponseEntity<String> send(
      TestRestTemplate rest, String body, String timestamp, String signature) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (timestamp != null) {
      headers.set(WebhookSignature.TIMESTAMP_HEADER, timestamp);
    }
    if (signature != null) {
      headers.set(WebhookSignature.SIGNATURE_HEADER, signature);
    }
    return rest.postForEntity(
        "/webhooks/psp",
        new HttpEntity<>(body.getBytes(StandardCharsets.UTF_8), headers),
        String.class);
  }
}
