package com.ledgerpay.mockpsp.operation;

public enum OperationType {
  AUTHORIZE,
  CAPTURE,
  VOID,
  REFUND;

  /** The type of operation this one must follow, or null for an authorisation. */
  public OperationType parentType() {
    return switch (this) {
      case AUTHORIZE -> null;
      case CAPTURE, VOID -> AUTHORIZE;
      case REFUND -> CAPTURE;
    };
  }
}
