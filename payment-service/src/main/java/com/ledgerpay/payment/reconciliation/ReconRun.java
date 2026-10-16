package com.ledgerpay.payment.reconciliation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A reconciliation run and the report version it is bound to (design §11.3). */
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
    Instant finishedAt) {

  public static final String RUNNING = "RUNNING";
  public static final String COMPLETED = "COMPLETED";
  public static final String FAILED = "FAILED";
}
