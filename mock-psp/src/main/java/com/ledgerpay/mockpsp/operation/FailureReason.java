package com.ledgerpay.mockpsp.operation;

/** Why the PSP declined an operation. A decline is a final FAILED outcome, not a request error. */
public enum FailureReason {
  /** Declined by fault injection ({@code DECLINE}), standing in for an issuer's refusal. */
  DECLINED,
  /** The authorisation or capture this operation follows has not succeeded. */
  PARENT_NOT_SUCCEEDED,
  /** Capture and void are for the full authorised amount; partial capture is out of scope. */
  AMOUNT_MISMATCH,
  CURRENCY_MISMATCH,
  /** The authorisation already has a pending or successful capture or void. */
  AUTHORIZATION_ALREADY_USED,
  /** Pending and successful refunds would exceed the captured amount. */
  REFUND_EXCEEDS_CAPTURE
}
