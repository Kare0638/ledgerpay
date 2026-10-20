package com.ledgerpay.payment.ledger.integrity;

/**
 * One way the books disagree with themselves.
 *
 * @param check which invariant is broken
 * @param subject what it concerns, such as {@code journal <id>} or {@code payment <id>}
 * @param detail what was found, with the expected and actual values
 */
public record Violation(Check check, String subject, String detail) {

  /** The invariants, in the order they are checked and reported (design §5.5, §11.2). */
  public enum Check {
    /** A succeeded capture or refund without its journal. */
    MISSING_JOURNAL,
    /** A journal for an operation that did not succeed, or of another type. */
    UNEXPECTED_JOURNAL,
    /** A journal with fewer than two postings, or whose postings do not sum to zero. */
    UNBALANCED_JOURNAL,
    /** All postings in a currency do not sum to zero. */
    LEDGER_NOT_ZERO,
    /**
     * A balanced journal that does not book its operation: the PSP receivable moved by another
     * amount, or the merchant's share went to another merchant, or another currency was used.
     */
    JOURNAL_OPERATION_MISMATCH,
    /** A payment's captured or refunded amount disagrees with its journals: a stale cache. */
    PAYMENT_TOTALS_MISMATCH
  }
}
