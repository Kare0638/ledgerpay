package com.ledgerpay.payment.payment;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;

/** A request that the payment's current status does not allow. */
public class InvalidStateException extends ApiException {

  private final PaymentStatus from;
  private final PaymentStatus to;

  public InvalidStateException(PaymentStatus from, PaymentStatus to) {
    super(ErrorCode.INVALID_STATE, "Payment in status " + from + " cannot move to " + to);
    this.from = from;
    this.to = to;
  }

  public PaymentStatus from() {
    return from;
  }

  public PaymentStatus to() {
    return to;
  }
}
