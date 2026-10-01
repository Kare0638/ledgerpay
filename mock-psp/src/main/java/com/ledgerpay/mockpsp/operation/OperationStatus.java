package com.ledgerpay.mockpsp.operation;

/** PENDING means accepted with the outcome still to come; the other two are final. */
public enum OperationStatus {
  PENDING,
  SUCCEEDED,
  FAILED
}
