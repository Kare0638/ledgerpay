package com.ledgerpay.mockpsp.operation;

/** The request was not accepted and nothing was recorded, unlike a decline. */
public class SubmitRejectedException extends RuntimeException {

  public enum Reason {
    /** Malformed beyond what bean validation can see, e.g. a parent on an authorisation. */
    INVALID_REQUEST,
    /** The parent does not exist, is the wrong type, or belongs to another merchant. */
    INVALID_REFERENCE,
    /** The same {@code psp_request_id} was already used with different parameters. */
    REQUEST_ID_CONFLICT
  }

  private final Reason reason;

  public SubmitRejectedException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
