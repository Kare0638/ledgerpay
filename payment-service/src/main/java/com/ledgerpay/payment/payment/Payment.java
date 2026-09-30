package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;
import java.util.UUID;

/** A payment as stored, with its captured, refunded and reserved amounts. */
public record Payment(
    UUID id,
    String merchantId,
    String merchantReference,
    Money amount,
    PaymentStatus status,
    Money captured,
    Money refunded,
    Money refundReserved) {

  /** captured − refunded − reserved (design §5.4). */
  public Money refundable() {
    return captured.minus(refunded).minus(refundReserved);
  }

  public RefundSummary refundSummary() {
    return RefundSummary.of(captured, refunded);
  }
}
