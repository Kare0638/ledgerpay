package com.ledgerpay.payment.reconciliation;

import java.time.Instant;
import java.util.UUID;

/**
 * One of our PSP operations as the run's snapshot saw it. Stored with the item, so a completed run
 * keeps showing what it compared even after the operation moves on.
 */
public record InternalOperation(
    UUID id,
    UUID paymentId,
    String merchantId,
    String type,
    String pspRequestId,
    String pspReference,
    long amountMinor,
    String currency,
    String status,
    Instant succeededAt) {

  boolean succeeded() {
    return status.equals("SUCCEEDED");
  }
}
