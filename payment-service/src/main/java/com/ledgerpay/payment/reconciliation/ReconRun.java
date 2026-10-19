package com.ledgerpay.payment.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A reconciliation run, the report version it is bound to and its ledger check (design §11). */
public record ReconRun(
    UUID runId,
    UUID reportId,
    String psp,
    LocalDate businessDate,
    int reportVersion,
    String status,
    String rulesVersion,
    Instant snapshotAt,
    ReconSummary summary,
    String error,
    Instant createdAt,
    Instant finishedAt,
    LedgerIntegrity ledgerIntegrity) {

  /**
   * The ledger integrity check that ran with the run (#19); null until it has, or if it could not.
   * A failed check is an alert of its own: the books disagree with themselves whatever the report
   * says.
   */
  public record LedgerIntegrity(UUID checkId, String status, int violationCount) {}

  public static final String RUNNING = "RUNNING";
  public static final String COMPLETED = "COMPLETED";
  public static final String FAILED = "FAILED";
}
