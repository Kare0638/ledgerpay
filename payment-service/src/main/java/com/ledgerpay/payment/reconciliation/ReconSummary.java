package com.ledgerpay.payment.reconciliation;

import java.util.Map;

/**
 * A run's totals (design §11.3). Equal nets never stand in for the item-level categories: a report
 * can net to our books exactly and still hold breaks.
 *
 * @param externalRows rows in the report
 * @param uniqueTransactions distinct PSP references in the report
 * @param external the report's captures, refunds and net
 * @param internal our succeeded captures and refunds of the day
 * @param categories every category, with its items, the report rows and our records in them, and
 *     their amount (the report's where it has the item, otherwise ours)
 */
public record ReconSummary(
    int externalRows,
    int uniqueTransactions,
    Totals external,
    Totals internal,
    Map<ReconCategory, CategoryTotal> categories) {

  public record Totals(long capturedMinor, long refundedMinor, long netMinor) {}

  public record CategoryTotal(int items, int externalRows, int internalRecords, long amountMinor) {}
}
