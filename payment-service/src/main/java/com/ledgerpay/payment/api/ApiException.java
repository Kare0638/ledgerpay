package com.ledgerpay.payment.api;

/** A failure that the API reports to the client with a specific {@link ErrorCode}. */
public class ApiException extends RuntimeException {

  private final ErrorCode code;

  public ApiException(ErrorCode code, String detail) {
    super(detail);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
