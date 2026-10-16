package com.ledgerpay.payment.reconciliation;

import com.ledgerpay.payment.reconciliation.ReconItem.Difference;
import com.ledgerpay.payment.reconciliation.ReconSummary.CategoryTotal;
import com.ledgerpay.payment.reconciliation.ReconSummary.Totals;
import com.ledgerpay.payment.settlement.SettlementRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Matches a settlement report against our operations and classifies every difference (design
 * §11.2). A pure function of its inputs: no database, no clock.
 *
 * <ul>
 *   <li>Rows are grouped by PSP reference. A reference on several rows is DUPLICATE_EXTERNAL as a
 *       whole; no row of the group is picked to match.
 *   <li>A row matches our operation with the same PSP reference, or failing that the same request
 *       ID. Never by amount and time: two £100 captures a second apart are not the same one.
 *   <li>No operation: MISSING_INTERNAL. Type, merchant, amount, currency or day differ:
 *       AMOUNT_MISMATCH. The fields agree but our operation is not SUCCEEDED: STATUS_MISMATCH.
 *       Otherwise MATCHED.
 *   <li>Our succeeded captures and refunds of the day left unmatched are MISSING_EXTERNAL.
 * </ul>
 *
 * <p>Each report row and each of our operations ends up in exactly one item, so the categories'
 * counts add up to the totals.
 */
public final class Reconciler {

  public static final String RULES_VERSION = "1";

  private Reconciler() {}

  public static List<ReconItem> classify(
      LocalDate businessDay, List<SettlementRow> rows, List<InternalOperation> operations) {
    Map<String, List<SettlementRow>> groups = new LinkedHashMap<>();
    rows.stream()
        .sorted(Comparator.comparingInt(SettlementRow::lineNo))
        .forEach(
            row -> groups.computeIfAbsent(row.pspReference(), r -> new ArrayList<>()).add(row));

    Map<String, InternalOperation> byReference = new HashMap<>();
    Map<String, InternalOperation> byRequestId = new HashMap<>();
    for (InternalOperation operation : operations) {
      if (operation.pspReference() != null) {
        byReference.put(operation.pspReference(), operation);
      }
      byRequestId.put(operation.pspRequestId(), operation);
    }

    List<ReconItem> items = new ArrayList<>();
    Set<UUID> matched = new HashSet<>();
    for (var group : groups.entrySet()) {
      String reference = group.getKey();
      List<SettlementRow> lines = group.getValue();
      List<Integer> lineNos = lines.stream().map(SettlementRow::lineNo).toList();
      SettlementRow row = lines.getFirst();
      InternalOperation operation = byReference.get(reference);
      if (operation == null && lines.size() == 1) {
        operation = byRequestId.get(row.pspRequestId());
      }
      if (operation != null && !matched.add(operation.id())) {
        // Already matched by another row: this row is a second claim on the same operation.
        items.add(
            item(items, ReconCategory.DUPLICATE_EXTERNAL, reference, lineNos, null, Map.of()));
        continue;
      }
      if (lines.size() > 1) {
        items.add(
            item(items, ReconCategory.DUPLICATE_EXTERNAL, reference, lineNos, operation, Map.of()));
      } else if (operation == null) {
        items.add(item(items, ReconCategory.MISSING_INTERNAL, reference, lineNos, null, Map.of()));
      } else {
        Map<String, Difference> diff = differences(row, operation, businessDay);
        if (!diff.isEmpty()) {
          items.add(
              item(items, ReconCategory.AMOUNT_MISMATCH, reference, lineNos, operation, diff));
        } else if (!operation.succeeded()) {
          items.add(
              item(
                  items,
                  ReconCategory.STATUS_MISMATCH,
                  reference,
                  lineNos,
                  operation,
                  Map.of("status", new Difference("SUCCEEDED", operation.status()))));
        } else {
          items.add(item(items, ReconCategory.MATCHED, reference, lineNos, operation, Map.of()));
        }
      }
    }

    operations.stream()
        .filter(operation -> !matched.contains(operation.id()))
        .filter(operation -> succeededOn(operation, businessDay))
        .sorted(
            Comparator.comparing(InternalOperation::succeededAt)
                .thenComparing(InternalOperation::id))
        .forEach(
            operation ->
                items.add(
                    item(
                        items,
                        ReconCategory.MISSING_EXTERNAL,
                        operation.pspReference(),
                        List.of(),
                        operation,
                        Map.of())));
    return List.copyOf(items);
  }

  /** What disagrees between the report's row and our operation, field by field. */
  static Map<String, Difference> differences(
      SettlementRow row, InternalOperation operation, LocalDate businessDay) {
    Map<String, Difference> diff = new LinkedHashMap<>();
    if (operation.pspReference() != null && !operation.pspReference().equals(row.pspReference())) {
      diff.put("psp_reference", new Difference(row.pspReference(), operation.pspReference()));
    }
    if (!operation.pspRequestId().equals(row.pspRequestId())) {
      diff.put("psp_request_id", new Difference(row.pspRequestId(), operation.pspRequestId()));
    }
    if (!operation.type().equals(row.txnType())) {
      diff.put("txn_type", new Difference(row.txnType(), operation.type()));
    }
    if (!operation.merchantId().equals(row.merchantId())) {
      diff.put("merchant_id", new Difference(row.merchantId(), operation.merchantId()));
    }
    if (operation.amountMinor() != row.amountMinor()) {
      diff.put("amount_minor", new Difference(row.amountMinor(), operation.amountMinor()));
    }
    if (!operation.currency().equals(row.currency())) {
      diff.put("currency", new Difference(row.currency(), operation.currency()));
    }
    // Only the day is compared: the report is per day, and we may record the time differently.
    if (operation.succeededAt() != null && !dayOf(operation.succeededAt()).equals(businessDay)) {
      diff.put(
          "succeeded_at",
          new Difference(row.succeededAt().toString(), operation.succeededAt().toString()));
    }
    return diff;
  }

  public static ReconSummary summarise(
      LocalDate businessDay,
      List<SettlementRow> rows,
      List<InternalOperation> operations,
      List<ReconItem> items) {
    Map<Integer, SettlementRow> byLine = new HashMap<>();
    rows.forEach(row -> byLine.put(row.lineNo(), row));

    Map<ReconCategory, CategoryTotal> categories = new EnumMap<>(ReconCategory.class);
    for (ReconCategory category : ReconCategory.values()) {
      categories.put(category, new CategoryTotal(0, 0, 0, 0));
    }
    for (ReconItem item : items) {
      long amount =
          item.externalLineNos().isEmpty()
              ? item.internal().amountMinor()
              : item.externalLineNos().stream().mapToLong(l -> byLine.get(l).amountMinor()).sum();
      categories.merge(
          item.category(),
          new CategoryTotal(
              1, item.externalLineNos().size(), item.internal() == null ? 0 : 1, amount),
          (a, b) ->
              new CategoryTotal(
                  a.items() + b.items(),
                  a.externalRows() + b.externalRows(),
                  a.internalRecords() + b.internalRecords(),
                  a.amountMinor() + b.amountMinor()));
    }

    long externalCaptured = sum(rows, "CAPTURE");
    long externalRefunded = sum(rows, "REFUND");
    List<InternalOperation> ours =
        operations.stream().filter(operation -> succeededOn(operation, businessDay)).toList();
    long internalCaptured = sumInternal(ours, "CAPTURE");
    long internalRefunded = sumInternal(ours, "REFUND");
    return new ReconSummary(
        rows.size(),
        (int) rows.stream().map(SettlementRow::pspReference).distinct().count(),
        new Totals(externalCaptured, externalRefunded, externalCaptured - externalRefunded),
        new Totals(internalCaptured, internalRefunded, internalCaptured - internalRefunded),
        categories);
  }

  /** A capture or refund of ours that succeeded on the business day: the report must have it. */
  static boolean succeededOn(InternalOperation operation, LocalDate businessDay) {
    return operation.succeeded()
        && (operation.type().equals("CAPTURE") || operation.type().equals("REFUND"))
        && dayOf(operation.succeededAt()).equals(businessDay);
  }

  private static LocalDate dayOf(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate();
  }

  private static long sum(List<SettlementRow> rows, String type) {
    return rows.stream()
        .filter(row -> row.txnType().equals(type))
        .mapToLong(SettlementRow::amountMinor)
        .sum();
  }

  private static long sumInternal(List<InternalOperation> operations, String type) {
    return operations.stream()
        .filter(operation -> operation.type().equals(type))
        .mapToLong(InternalOperation::amountMinor)
        .sum();
  }

  private static ReconItem item(
      List<ReconItem> items,
      ReconCategory category,
      String reference,
      List<Integer> lineNos,
      InternalOperation operation,
      Map<String, Difference> diff) {
    return new ReconItem(items.size() + 1, category, reference, lineNos, operation, diff);
  }
}
