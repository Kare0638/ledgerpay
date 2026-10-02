package com.ledgerpay.mockpsp.fault;

/** The failures mock-psp can produce on demand (design §9.4). */
public enum Fault {
  /** The operation is declined at once: FAILED with {@code DECLINED}, reported by webhook. */
  DECLINE,
  /**
   * The operation is recorded and succeeds as usual, but the submit response is held back past the
   * caller's timeout, so the caller cannot know: it must inquire, not resubmit.
   */
  TIMEOUT_AFTER_COMMIT,
  /** The outcome is never sent; only an inquiry or reconciliation can find it. */
  DROP_WEBHOOK,
  /** The outcome is sent ten times under one event ID, and twice more under new event IDs. */
  DUPLICATE_WEBHOOK,
  /** After the outcome, an older PENDING event for the same operation arrives. */
  OUT_OF_ORDER,
  /** The outcome is sent late. */
  DELAY_WEBHOOK
}
