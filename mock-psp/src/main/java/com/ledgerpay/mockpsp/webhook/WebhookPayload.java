package com.ledgerpay.mockpsp.webhook;

import com.ledgerpay.mockpsp.operation.Operation;
import java.time.Instant;

/** The webhook body (design §9.2); serialised once and stored, so redeliveries are identical. */
record WebhookPayload(
    String eventId,
    String eventType,
    String pspRequestId,
    String pspReference,
    String merchantId,
    long amountMinor,
    String currency,
    String status,
    Instant occurredAt,
    int resourceVersion) {

  static WebhookPayload of(String eventId, Operation operation) {
    return new WebhookPayload(
        eventId,
        operation.type().name().toLowerCase() + "." + operation.status().name().toLowerCase(),
        operation.pspRequestId(),
        operation.pspReference(),
        operation.merchantId(),
        operation.amountMinor(),
        operation.currency(),
        operation.status().name(),
        operation.succeededAt() != null ? operation.succeededAt() : operation.updatedAt(),
        operation.resourceVersion());
  }
}
