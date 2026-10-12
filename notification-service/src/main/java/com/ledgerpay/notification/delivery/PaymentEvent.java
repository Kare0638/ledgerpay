package com.ledgerpay.notification.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/**
 * The fields of a payment event that delivery needs, read from payment-service's envelope (ADR
 * 0006): {@code {eventId, eventType, paymentId, occurredAt, data}}. The merchant webhook carries
 * the envelope exactly as it was published.
 */
record PaymentEvent(UUID eventId, String eventType, UUID paymentId, String merchantId, String raw) {

  static PaymentEvent parse(ObjectMapper json, String value) {
    if (value == null) {
      throw new MalformedEventException("no value");
    }
    JsonNode envelope;
    try {
      envelope = json.readTree(value);
    } catch (JsonProcessingException e) {
      throw new MalformedEventException("not JSON: " + e.getOriginalMessage());
    }
    return new PaymentEvent(
        uuid(envelope, "eventId"),
        text(envelope, "eventType"),
        uuid(envelope, "paymentId"),
        text(envelope.path("data"), "merchantId"),
        value);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new MalformedEventException("missing " + field);
    }
    return value.asText();
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return UUID.fromString(text(node, field));
    } catch (IllegalArgumentException e) {
      throw new MalformedEventException(field + " is not a UUID");
    }
  }
}
