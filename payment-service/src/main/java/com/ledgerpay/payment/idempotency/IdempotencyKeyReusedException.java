package com.ledgerpay.payment.idempotency;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;

public class IdempotencyKeyReusedException extends ApiException {

  public IdempotencyKeyReusedException() {
    super(
        ErrorCode.IDEMPOTENCY_KEY_REUSED,
        "This Idempotency-Key was already used for a different request");
  }
}
