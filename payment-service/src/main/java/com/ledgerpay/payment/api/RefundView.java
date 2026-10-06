package com.ledgerpay.payment.api;

import com.ledgerpay.payment.payment.Refund;
import java.util.UUID;

/** {@code GET /v1/refunds/{id}}. */
public record RefundView(
    UUID refundId,
    UUID paymentId,
    String merchantReference,
    String status,
    long amountMinor,
    String currency,
    String reason) {

  static RefundView of(Refund refund) {
    return new RefundView(
        refund.id(),
        refund.paymentId(),
        refund.merchantReference(),
        refund.status().name(),
        refund.amount().minor(),
        refund.amount().currency().getCurrencyCode(),
        refund.reason());
  }
}
