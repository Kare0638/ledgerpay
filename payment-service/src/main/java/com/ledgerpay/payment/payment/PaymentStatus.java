package com.ledgerpay.payment.payment;

/**
 * Lifecycle of a payment (design §5.3). Every legal transition is defined in {@link
 * #canTransitionTo}; anything else is rejected with 409 {@code INVALID_STATE}.
 *
 * <pre>
 * AUTH_PENDING    → AUTHORIZED | DECLINED
 * AUTHORIZED      → CAPTURE_PENDING | VOID_PENDING
 * CAPTURE_PENDING → CAPTURED | AUTHORIZED (capture rejected)
 * VOID_PENDING    → VOIDED   | AUTHORIZED (void rejected)
 * DECLINED, CAPTURED, VOIDED → nothing
 * </pre>
 *
 * <p>An explicit rejection of a capture or a void returns the payment to AUTHORIZED: no money
 * moved, the authorisation still holds, and the merchant may retry. A timeout is not a rejection;
 * it keeps the pending status until the PSP is inquired (design §9.1).
 *
 * <p>Refunds do not change the payment status: a captured payment stays CAPTURED and its refund
 * progress is reported separately as a {@link RefundSummary}.
 */
public enum PaymentStatus {
  AUTH_PENDING,
  AUTHORIZED,
  DECLINED,
  CAPTURE_PENDING,
  CAPTURED,
  VOID_PENDING,
  VOIDED;

  /**
   * Whether a payment in this status may move to {@code target}. A pure function: no state, no side
   * effects. Staying in the same status is never a transition; replaying the same outcome is
   * handled by the money transaction's idempotency, not here.
   */
  public boolean canTransitionTo(PaymentStatus target) {
    return switch (this) {
      case AUTH_PENDING -> target == AUTHORIZED || target == DECLINED;
      case AUTHORIZED -> target == CAPTURE_PENDING || target == VOID_PENDING;
      case CAPTURE_PENDING -> target == CAPTURED || target == AUTHORIZED;
      case VOID_PENDING -> target == VOIDED || target == AUTHORIZED;
      case DECLINED, CAPTURED, VOIDED -> false;
    };
  }

  /**
   * Returns {@code target} if the transition is legal.
   *
   * @throws InvalidStateException otherwise, which the API maps to 409 {@code INVALID_STATE}
   */
  public PaymentStatus transitionTo(PaymentStatus target) {
    if (!canTransitionTo(target)) {
      throw new InvalidStateException(this, target);
    }
    return target;
  }
}
