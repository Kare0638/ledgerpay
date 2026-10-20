package com.ledgerpay.payment.ledger.integrity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stored ledger integrity check.
 *
 * @param reconRunId the reconciliation run it ran with, when it did
 * @param violationCount every violation found; {@code violations} holds the first of them
 */
public record IntegrityCheck(
    UUID checkId,
    Trigger trigger,
    UUID reconRunId,
    String status,
    Instant snapshotAt,
    long journals,
    long postings,
    int violationCount,
    List<Violation> violations,
    Instant checkedAt) {

  public static final String PASSED = "PASSED";
  public static final String FAILED = "FAILED";

  public enum Trigger {
    ON_DEMAND,
    RECON_RUN
  }

  public boolean passed() {
    return status.equals(PASSED);
  }
}
