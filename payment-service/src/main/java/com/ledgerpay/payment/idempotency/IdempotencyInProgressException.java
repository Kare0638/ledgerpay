package com.ledgerpay.payment.idempotency;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;

public class IdempotencyInProgressException extends ApiException {

  public IdempotencyInProgressException() {
    super(
        ErrorCode.IDEMPOTENCY_IN_PROGRESS,
        "A request with this Idempotency-Key is still in progress; retry with the same key");
  }
}
