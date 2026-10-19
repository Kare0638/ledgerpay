package com.ledgerpay.payment.ledger.integrity;

import java.time.Instant;
import java.util.List;

/**
 * What one read of the books found.
 *
 * @param snapshotAt when the books were read
 * @param journals journals checked
 * @param postings postings checked
 * @param violations every violation found, in check order
 */
public record IntegrityResult(
    Instant snapshotAt, long journals, long postings, List<Violation> violations) {

  public boolean passed() {
    return violations.isEmpty();
  }
}
