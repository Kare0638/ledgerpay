package com.ledgerpay.payment.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ledgerpay.payment.reconciliation.ReconItem.Difference;
import com.ledgerpay.payment.reconciliation.ReconSummary.CategoryTotal;
import com.ledgerpay.payment.settlement.SettlementRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

  static final LocalDate DAY = LocalDate.parse("2026-10-13");
  static final Instant NOON = Instant.parse("2026-10-13T12:00:00Z");

  static InternalOperation op(String reference, String type, long amount, String status) {
    return new InternalOperation(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "m_1",
        type,
        "req_" + reference,
        reference,
        amount,
        "GBP",
        status,
        status.equals("SUCCEEDED") ? NOON : null);
  }

  static SettlementRow row(int line, InternalOperation op) {
    return new SettlementRow(
        line,
        op.pspReference(),
        op.pspRequestId(),
        op.type(),
        op.merchantId(),
        op.amountMinor(),
        op.currency(),
        NOON);
  }

  static SettlementRow row(int line, String reference, String type, long amount) {
    return new SettlementRow(line, reference, "req_" + reference, type, "m_1", amount, "GBP", NOON);
  }

  static List<ReconItem> classify(List<SettlementRow> rows, List<InternalOperation> ops) {
    return Reconciler.classify(DAY, rows, ops);
  }

  @Test
  void rowsThatAgreeWithOurSucceededOperationsAreMatched() {
    var capture = op("psp_1", "CAPTURE", 10000, "SUCCEEDED");
    var refund = op("psp_2", "REFUND", 3000, "SUCCEEDED");

    List<ReconItem> items =
        classify(List.of(row(2, capture), row(3, refund)), List.of(capture, refund));

    assertThat(items)
        .extracting(
            ReconItem::itemNo,
            ReconItem::category,
            ReconItem::externalLineNos,
            ReconItem::pspOperationId)
        .containsExactly(
            tuple(1, ReconCategory.MATCHED, List.of(2), capture.id()),
            tuple(2, ReconCategory.MATCHED, List.of(3), refund.id()));
  }

  @Test
  void aReferenceOnSeveralRowsIsOneDuplicateBreakWithNoRowPickedToMatch() {
    var capture = op("psp_1", "CAPTURE", 10000, "SUCCEEDED");

    List<ReconItem> items = classify(List.of(row(2, capture), row(3, capture)), List.of(capture));

    assertThat(items).hasSize(1);
    assertThat(items.getFirst().category()).isEqualTo(ReconCategory.DUPLICATE_EXTERNAL);
    assertThat(items.getFirst().externalLineNos()).containsExactly(2, 3);
    assertThat(items.getFirst().pspOperationId()).isEqualTo(capture.id());
  }

  @Test
  void aRowWeHaveNoRecordOfIsMissingInternal() {
    List<ReconItem> items = classify(List.of(row(2, "psp_unknown", "CAPTURE", 500)), List.of());

    assertThat(items)
        .extracting(ReconItem::category, ReconItem::pspOperationId)
        .containsExactly(tuple(ReconCategory.MISSING_INTERNAL, null));
  }

  @Test
  void ourSucceededOperationAbsentFromTheReportIsMissingExternal() {
    var capture = op("psp_1", "CAPTURE", 10000, "SUCCEEDED");
    var otherDay =
        new InternalOperation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "m_1",
            "CAPTURE",
            "req_x",
            "psp_x",
            1,
            "GBP",
            "SUCCEEDED",
            Instant.parse("2026-10-14T00:00:00Z"));
    var pending = op("psp_p", "CAPTURE", 1, "PENDING");
    var authorisation = op("psp_a", "AUTHORIZE", 1, "SUCCEEDED");

    List<ReconItem> items = classify(List.of(), List.of(capture, otherDay, pending, authorisation));

    assertThat(items)
        .extracting(ReconItem::category, ReconItem::pspOperationId)
        .containsExactly(tuple(ReconCategory.MISSING_EXTERNAL, capture.id()));
  }

  @Test
  void aMatchedRowWithADifferentAmountIsAMismatchWithAFieldLevelDiff() {
    var capture = op("psp_1", "CAPTURE", 5000, "SUCCEEDED");

    ReconItem item =
        classify(List.of(row(2, "psp_1", "CAPTURE", 5100)), List.of(capture)).getFirst();

    assertThat(item.category()).isEqualTo(ReconCategory.AMOUNT_MISMATCH);
    assertThat(item.diff()).isEqualTo(Map.of("amount_minor", new Difference(5100L, 5000L)));
  }

  @Test
  void merchantTypeCurrencyAndDayConflictsAreFieldConflictsToo() {
    var capture =
        new InternalOperation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "m_2",
            "REFUND",
            "req_psp_1",
            "psp_1",
            100,
            "EUR",
            "SUCCEEDED",
            Instant.parse("2026-10-12T23:00:00Z"));

    ReconItem item =
        classify(List.of(row(2, "psp_1", "CAPTURE", 100)), List.of(capture)).getFirst();

    assertThat(item.category()).isEqualTo(ReconCategory.AMOUNT_MISMATCH);
    assertThat(item.diff().keySet())
        .containsExactly("txn_type", "merchant_id", "currency", "succeeded_at");
  }

  @Test
  void aFieldConflictComesBeforeAStatusConflict() {
    var pending = op("psp_1", "CAPTURE", 5000, "PENDING");

    ReconItem item =
        classify(List.of(row(2, "psp_1", "CAPTURE", 5100)), List.of(pending)).getFirst();

    assertThat(item.category()).isEqualTo(ReconCategory.AMOUNT_MISMATCH);
  }

  @Test
  void succeededAtThePspButPendingOrFailedHereIsAStatusMismatch() {
    var pending = op("psp_1", "CAPTURE", 10000, "PENDING");
    var failed = op("psp_2", "REFUND", 3000, "FAILED");

    List<ReconItem> items =
        classify(List.of(row(2, pending), row(3, failed)), List.of(pending, failed));

    assertThat(items)
        .extracting(ReconItem::category)
        .containsExactly(ReconCategory.STATUS_MISMATCH, ReconCategory.STATUS_MISMATCH);
    assertThat(items.get(0).diff())
        .isEqualTo(Map.of("status", new Difference("SUCCEEDED", "PENDING")));
    assertThat(items.get(1).diff())
        .isEqualTo(Map.of("status", new Difference("SUCCEEDED", "FAILED")));
  }

  @Test
  void withoutOurReferenceTheRowMatchesOnTheRequestId() {
    // The PSP's acceptance never reached us, so we have no reference for the operation yet.
    var capture =
        new InternalOperation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "m_1",
            "CAPTURE",
            "req_psp_1",
            null,
            10000,
            "GBP",
            "PENDING",
            null);

    ReconItem item =
        classify(List.of(row(2, "psp_1", "CAPTURE", 10000)), List.of(capture)).getFirst();

    assertThat(item.category()).isEqualTo(ReconCategory.STATUS_MISMATCH);
    assertThat(item.pspOperationId()).isEqualTo(capture.id());
  }

  @Test
  void aRequestIdMatchUnderAnotherReferenceIsAConflict() {
    var capture = op("psp_ours", "CAPTURE", 10000, "SUCCEEDED");
    var row =
        new SettlementRow(2, "psp_theirs", "req_psp_ours", "CAPTURE", "m_1", 10000, "GBP", NOON);

    ReconItem item = classify(List.of(row), List.of(capture)).getFirst();

    assertThat(item.category()).isEqualTo(ReconCategory.AMOUNT_MISMATCH);
    assertThat(item.diff()).containsOnlyKeys("psp_reference");
  }

  /**
   * Line 2 has a wrong reference but X's request ID; line 3 names X exactly. The exact match keeps
   * X, and line 2 is a second claim on it, not the other way round.
   */
  @Test
  void anExactReferenceMatchIsNotTakenByAnEarlierRowsRequestId() {
    var x = op("psp_x", "CAPTURE", 10000, "SUCCEEDED");
    var wrongReference =
        new SettlementRow(2, "psp_typo", "req_psp_x", "CAPTURE", "m_1", 10000, "GBP", NOON);

    List<ReconItem> items = classify(List.of(wrongReference, row(3, x)), List.of(x));

    assertThat(items)
        .extracting(ReconItem::externalLineNos, ReconItem::category, ReconItem::pspOperationId)
        .containsExactly(
            tuple(List.of(2), ReconCategory.DUPLICATE_EXTERNAL, null),
            tuple(List.of(3), ReconCategory.MATCHED, x.id()));
  }

  /**
   * A duplicated reference we do not know, whose rows all carry the request ID of our pending
   * operation: the break names that operation rather than leaving it out of every item.
   */
  @Test
  void aDuplicateGroupWithOneRequestIdIsLinkedToItsOperation() {
    var pending =
        new InternalOperation(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "m_1",
            "CAPTURE",
            "req_psp_1",
            null,
            10000,
            "GBP",
            "PENDING",
            null);

    List<ReconItem> items =
        classify(
            List.of(row(2, "psp_1", "CAPTURE", 10000), row(3, "psp_1", "CAPTURE", 10000)),
            List.of(pending));

    assertThat(items).singleElement();
    assertThat(items.getFirst().category()).isEqualTo(ReconCategory.DUPLICATE_EXTERNAL);
    assertThat(items.getFirst().pspOperationId()).isEqualTo(pending.id());
  }

  @Test
  void neverMatchesOnAmountAndTimeAlone() {
    var ours = op("psp_ours", "CAPTURE", 10000, "SUCCEEDED");

    List<ReconItem> items = classify(List.of(row(2, "psp_other", "CAPTURE", 10000)), List.of(ours));

    assertThat(items)
        .extracting(ReconItem::category)
        .containsExactly(ReconCategory.MISSING_INTERNAL, ReconCategory.MISSING_EXTERNAL);
  }

  @Test
  void equalNetTotalsDoNotHideItemLevelBreaks() {
    var p1 = op("psp_1", "CAPTURE", 10000, "SUCCEEDED");
    var p3 = op("psp_3", "CAPTURE", 5000, "SUCCEEDED");
    List<SettlementRow> rows =
        List.of(row(2, "psp_1", "CAPTURE", 10100), row(3, "psp_3", "CAPTURE", 4900));

    List<ReconItem> items = classify(rows, List.of(p1, p3));
    ReconSummary summary = Reconciler.summarise(DAY, rows, List.of(p1, p3), items);

    assertThat(summary.external().netMinor())
        .isEqualTo(summary.internal().netMinor())
        .isEqualTo(15000);
    assertThat(summary.categories().get(ReconCategory.AMOUNT_MISMATCH))
        .isEqualTo(new CategoryTotal(2, 2, 2, 15000));
    assertThat(summary.categories().get(ReconCategory.MATCHED).items()).isZero();
  }

  @Test
  void theSummaryHasTheReportsTotalsOursAndEveryCategory() {
    var capture = op("psp_1", "CAPTURE", 10000, "SUCCEEDED");
    var refund = op("psp_2", "REFUND", 3000, "SUCCEEDED");
    var lost = op("psp_3", "CAPTURE", 20000, "PENDING");
    List<SettlementRow> rows =
        List.of(
            row(2, capture),
            row(3, refund),
            row(4, lost),
            row(5, lost),
            row(6, "psp_9", "REFUND", 1));
    List<InternalOperation> ops = List.of(capture, refund, lost);

    ReconSummary summary = Reconciler.summarise(DAY, rows, ops, classify(rows, ops));

    assertThat(summary.externalRows()).isEqualTo(5);
    assertThat(summary.uniqueTransactions()).isEqualTo(4);
    assertThat(summary.external()).isEqualTo(new ReconSummary.Totals(50000, 3001, 46999));
    assertThat(summary.internal()).isEqualTo(new ReconSummary.Totals(10000, 3000, 7000));
    assertThat(summary.categories())
        .containsOnlyKeys(ReconCategory.values())
        .containsEntry(ReconCategory.MATCHED, new CategoryTotal(2, 2, 2, 13000))
        .containsEntry(ReconCategory.DUPLICATE_EXTERNAL, new CategoryTotal(1, 2, 1, 40000))
        .containsEntry(ReconCategory.MISSING_INTERNAL, new CategoryTotal(1, 1, 0, 1))
        .containsEntry(ReconCategory.MISSING_EXTERNAL, new CategoryTotal(0, 0, 0, 0));
  }

  /**
   * Any report against any books: every row and every operation the run must account for is in
   * exactly one item, so the categories add up to the totals.
   */
  @Property(tries = 500)
  void everyRowAndEveryOperationIsInExactlyOneItem(
      @ForAll @IntRange(min = 0, max = 12) int operations,
      @ForAll @IntRange(min = 0, max = 12) int extraRows,
      @ForAll("seeds") long seed) {
    var random = new Random(seed);
    String[] statuses = {"SUCCEEDED", "SUCCEEDED", "PENDING", "FAILED"};
    String[] types = {"CAPTURE", "REFUND", "AUTHORIZE"};
    List<InternalOperation> ops = new ArrayList<>();
    for (int i = 0; i < operations; i++) {
      String status = statuses[random.nextInt(statuses.length)];
      // Succeeded the day before, on the day or the day after; only a success has a time.
      Instant succeededAt =
          status.equals("SUCCEEDED") ? NOON.plusSeconds((random.nextInt(3) - 1) * 86400L) : null;
      ops.add(
          new InternalOperation(
              UUID.randomUUID(),
              UUID.randomUUID(),
              "m_" + random.nextInt(2),
              types[random.nextInt(types.length)],
              "req_" + i,
              random.nextInt(5) == 0 ? null : "psp_" + i,
              1 + random.nextInt(3),
              "GBP",
              status,
              succeededAt));
    }
    List<SettlementRow> rows = new ArrayList<>();
    int line = 2;
    for (int i = 0; i < operations + extraRows; i++) {
      // Reference and request ID point at operations independently, as a corrupt report could.
      int target = random.nextInt(operations + 3);
      rows.add(
          new SettlementRow(
              line++,
              "psp_" + target,
              random.nextBoolean() ? "req_" + target : "req_" + random.nextInt(operations + 3),
              random.nextBoolean() ? "CAPTURE" : "REFUND",
              "m_" + random.nextInt(2),
              1 + random.nextInt(3),
              "GBP",
              NOON));
    }
    List<ReconItem> items = Reconciler.classify(DAY, rows, ops);
    ReconSummary summary = Reconciler.summarise(DAY, rows, ops, items);

    List<Integer> lines = items.stream().flatMap(i -> i.externalLineNos().stream()).toList();
    assertThat(lines).doesNotHaveDuplicates().hasSize(rows.size());
    List<UUID> matchedOps =
        items.stream().map(ReconItem::pspOperationId).filter(id -> id != null).toList();
    assertThat(matchedOps).doesNotHaveDuplicates();
    Set<UUID> required = new HashSet<>();
    ops.stream().filter(o -> Reconciler.succeededOn(o, DAY)).forEach(o -> required.add(o.id()));
    assertThat(matchedOps).containsAll(required);
    // A row alone on a reference we have is always given that operation, wherever it is in the
    // file: no other row can take it by request ID.
    Map<String, Long> perReference =
        rows.stream()
            .collect(Collectors.groupingBy(SettlementRow::pspReference, Collectors.counting()));
    for (SettlementRow row : rows) {
      ops.stream()
          .filter(o -> row.pspReference().equals(o.pspReference()))
          .findFirst()
          .filter(o -> perReference.get(row.pspReference()) == 1)
          .ifPresent(
              o ->
                  assertThat(items)
                      .filteredOn(i -> i.externalLineNos().contains(row.lineNo()))
                      .singleElement()
                      .extracting(ReconItem::pspOperationId)
                      .isEqualTo(o.id()));
    }
    assertThat(items)
        .extracting(ReconItem::itemNo)
        .containsExactlyElementsOf(IntStream.rangeClosed(1, items.size()).boxed().toList());

    int itemsCounted = summary.categories().values().stream().mapToInt(CategoryTotal::items).sum();
    int rowsCounted =
        summary.categories().values().stream().mapToInt(CategoryTotal::externalRows).sum();
    int opsCounted =
        summary.categories().values().stream().mapToInt(CategoryTotal::internalRecords).sum();
    assertThat(itemsCounted).isEqualTo(items.size());
    assertThat(rowsCounted).isEqualTo(rows.size());
    assertThat(opsCounted).isEqualTo(matchedOps.size());
  }

  @Provide
  Arbitrary<Long> seeds() {
    return Arbitraries.longs();
  }
}
