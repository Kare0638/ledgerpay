package com.ledgerpay.payment.psp;

import java.time.Instant;

/** What a PSP call established. Only {@link Final} carries an outcome; the rest leave it open. */
public sealed interface PspResult {

  /** The PSP has the operation but no outcome yet; a webhook or inquiry will bring it. */
  record Accepted(String pspReference) implements PspResult {}

  /**
   * The PSP's final answer. A FAILED outcome is an explicit rejection, which is never the same as a
   * timeout.
   */
  record Final(
      String pspReference,
      boolean succeeded,
      String failureReason,
      Instant succeededAt,
      int resourceVersion)
      implements PspResult {}

  /** Inquiry only: the PSP has no record, so submitting again cannot move money twice. */
  record NotFound() implements PspResult {}

  /**
   * No answer: timeout, connection error, 5xx, or a response that makes no sense. The outcome is
   * unknown and must be inquired, never assumed.
   */
  record Unknown(String error) implements PspResult {}
}
