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

  /**
   * The event that reported the operation as accepted, one version before its outcome: what a PSP
   * delivering out of order sends after the outcome ({@code OUT_OF_ORDER}).
   */
  static WebhookPayload stale(String eventId, Operation operation) {
    if (operation.resourceVersion() <= 1) {
      throw new IllegalArgumentException(
          operation.pspRequestId() + " was never PENDING under an older version");
    }
    return new WebhookPayload(
        eventId,
        operation.type().name().toLowerCase() + ".pending",
        operation.pspRequestId(),
        operation.pspReference(),
        operation.merchantId(),
        operation.amountMinor(),
        operation.currency(),
        "PENDING",
        operation.createdAt(),
        operation.resourceVersion() - 1);
  }

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
