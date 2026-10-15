package com.ledgerpay.payment.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One uploaded version of a PSP's report for a business day (design §11.1). Immutable once stored.
 *
 * @param rowCount data rows in the file; for a VALID report, the rows stored
 * @param errorCount every error found; {@code errors} holds the first of them
 */
public record SettlementReport(
    UUID reportId,
    String psp,
    LocalDate businessDate,
    int version,
    String status,
    int rowCount,
    int errorCount,
    List<LineError> errors,
    String fileSha256,
    Instant uploadedAt) {

  public static final String VALID = "VALID";
  public static final String INVALID = "INVALID";

  public boolean valid() {
    return status.equals(VALID);
  }
}
