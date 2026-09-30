package com.ledgerpay.payment.api;

import com.ledgerpay.payment.payment.Payment;
import java.util.UUID;

/** {@code GET /v1/payments/{id}}: status, captured, refunded, reserved and refundable amounts. */
public record PaymentView(
    UUID paymentId,
    String merchantReference,
    String status,
    long amountMinor,
    String currency,
    long capturedMinor,
    long refundedMinor,
    long refundReservedMinor,
    long refundableMinor,
    String refundSummary) {

  static PaymentView of(Payment payment) {
    return new PaymentView(
        payment.id(),
        payment.merchantReference(),
        payment.status().name(),
        payment.amount().minor(),
        payment.amount().currency().getCurrencyCode(),
        payment.captured().minor(),
        payment.refunded().minor(),
        payment.refundReserved().minor(),
        payment.refundable().minor(),
        payment.refundSummary().name());
  }
}
