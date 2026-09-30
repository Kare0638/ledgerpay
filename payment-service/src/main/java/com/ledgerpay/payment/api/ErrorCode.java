package com.ledgerpay.payment.api;

import org.springframework.http.HttpStatus;

/** Error codes returned in the {@code code} field of a Problem Details response (design §7). */
public enum ErrorCode {
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
  INVALID_CSV(HttpStatus.BAD_REQUEST),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
  FORBIDDEN(HttpStatus.FORBIDDEN),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
  NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
  IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT),
  INVALID_STATE(HttpStatus.CONFLICT),
  REFUND_AMOUNT_EXCEEDED(HttpStatus.CONFLICT),
  DUPLICATE_REFERENCE(HttpStatus.CONFLICT),
  PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
  IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
  TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

  private final HttpStatus status;

  ErrorCode(HttpStatus status) {
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }
}
