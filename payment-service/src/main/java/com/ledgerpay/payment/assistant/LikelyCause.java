package com.ledgerpay.payment.assistant;

/** What the assistant may conclude about a break; anything else fails validation. */
public enum LikelyCause {
  /** The PSP reached an outcome we never received: a lost or late webhook. */
  OUTCOME_NOT_RECEIVED,
  /** The PSP and we hold different final outcomes for the same operation. */
  CONFLICTING_OUTCOME,
  /** The report lists the same transaction more than once. */
  DUPLICATE_IN_REPORT,
  /** We have a success on the day that the report does not list. */
  MISSING_FROM_REPORT,
  /** The report and our record disagree on amount, merchant, type, currency or day. */
  FIELD_MISMATCH,
  /** The report has a transaction we have no record of. */
  UNKNOWN_TRANSACTION,
  /** The facts do not support a conclusion. */
  INSUFFICIENT_EVIDENCE
}
