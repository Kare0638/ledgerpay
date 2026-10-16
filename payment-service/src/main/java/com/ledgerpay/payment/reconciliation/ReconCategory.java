package com.ledgerpay.payment.reconciliation;

/**
 * Where an item of a run ends up (design §11.2). An item takes the first category that applies, in
 * this order: duplicate, then unmatched, then a field conflict, then a status conflict.
 */
public enum ReconCategory {
  /** One-to-one; type, amount, currency, merchant and day agree, and we have it as succeeded. */
  MATCHED,
  /** One PSP reference on several rows of the report: the whole group is a break. */
  DUPLICATE_EXTERNAL,
  /** In the report, not found in our books. Nothing is ever created from the report. */
  MISSING_INTERNAL,
  /** Succeeded in our books that day, absent from the report. */
  MISSING_EXTERNAL,
  /** Matched, but type, amount, currency, merchant or day differ: see the item's diff. */
  AMOUNT_MISMATCH,
  /** Matched and the fields agree, but our operation is still PENDING or FAILED. */
  STATUS_MISMATCH
}
