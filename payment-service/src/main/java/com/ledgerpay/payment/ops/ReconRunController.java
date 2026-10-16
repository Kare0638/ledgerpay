package com.ledgerpay.payment.ops;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;
import com.ledgerpay.payment.api.ResourceNotFoundException;
import com.ledgerpay.payment.reconciliation.InternalOperation;
import com.ledgerpay.payment.reconciliation.ReconCategory;
import com.ledgerpay.payment.reconciliation.ReconEngine;
import com.ledgerpay.payment.reconciliation.ReconItem;
import com.ledgerpay.payment.reconciliation.ReconRun;
import com.ledgerpay.payment.reconciliation.ReconRuns;
import com.ledgerpay.payment.settlement.SettlementReport;
import com.ledgerpay.payment.settlement.SettlementReports;
import com.ledgerpay.payment.settlement.SettlementRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation runs (design §7, §11), behind the ops key. A run is started on one VALID report
 * version and finishes in the background; its summary and items never change afterwards.
 */
@RestController
@RequestMapping("/ops/recon-runs")
class ReconRunController {

  static final int MAX_PAGE = 1_000;

  record StartRun(@NotNull UUID reportId) {}

  /** An item with the report lines it concerns. */
  record ItemView(
      int itemNo,
      ReconCategory category,
      String pspReference,
      UUID pspOperationId,
      UUID paymentId,
      List<SettlementRow> externalLines,
      InternalOperation internal,
      Map<String, ReconItem.Difference> diff) {}

  record ItemPage(
      UUID runId,
      String runStatus,
      ReconCategory category,
      long total,
      int offset,
      int limit,
      List<ItemView> items) {}

  private final ReconEngine engine;
  private final ReconRuns runs;
  private final SettlementReports reports;

  ReconRunController(ReconEngine engine, ReconRuns runs, SettlementReports reports) {
    this.engine = engine;
    this.runs = runs;
    this.reports = reports;
  }

  /** 202 with the RUNNING run; poll its URL until it is COMPLETED or FAILED. */
  @PostMapping
  ResponseEntity<ReconRun> start(@Valid @RequestBody StartRun request) {
    SettlementReport report =
        reports
            .find(request.reportId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Settlement report " + request.reportId()));
    if (!report.valid()) {
      throw new ApiException(
          ErrorCode.INVALID_STATE,
          "Settlement report "
              + report.reportId()
              + " is INVALID and cannot be reconciled; upload a corrected file");
    }
    ReconRun run = engine.start(report);
    return ResponseEntity.accepted()
        .location(URI.create("/ops/recon-runs/" + run.runId()))
        .body(run);
  }

  @GetMapping("/{id}")
  ReconRun get(@PathVariable UUID id) {
    return runs.find(id).orElseThrow(() -> new ResourceNotFoundException("Recon run " + id));
  }

  /** Every run of a report, oldest first: earlier runs are kept for comparison. */
  @GetMapping
  List<ReconRun> forReport(@RequestParam UUID reportId) {
    return runs.forReport(reportId);
  }

  @GetMapping("/{id}/items")
  ItemPage items(
      @PathVariable UUID id,
      @RequestParam(required = false) ReconCategory category,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit) {
    if (offset < 0 || limit < 1 || limit > MAX_PAGE) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR, "offset must be 0 or more and limit 1 to " + MAX_PAGE);
    }
    ReconRun run = get(id);
    List<ReconItem> items = runs.items(id, category, offset, limit);
    Map<Integer, SettlementRow> lines =
        reports
            .rowsAt(
                run.reportId(),
                items.stream().flatMap(item -> item.externalLineNos().stream()).toList())
            .stream()
            .collect(Collectors.toMap(SettlementRow::lineNo, Function.identity()));
    return new ItemPage(
        id,
        run.status(),
        category,
        runs.countItems(id, category),
        offset,
        limit,
        items.stream()
            .map(
                item ->
                    new ItemView(
                        item.itemNo(),
                        item.category(),
                        item.pspReference(),
                        item.pspOperationId(),
                        item.internal() == null ? null : item.internal().paymentId(),
                        item.externalLineNos().stream().map(lines::get).toList(),
                        item.internal(),
                        item.diff()))
            .toList());
  }
}
