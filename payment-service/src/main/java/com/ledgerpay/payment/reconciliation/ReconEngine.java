package com.ledgerpay.payment.reconciliation;

import com.ledgerpay.payment.settlement.SettlementReport;
import com.ledgerpay.payment.settlement.SettlementReports;
import com.ledgerpay.payment.settlement.SettlementRow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs reconciliations (design §11.2, ADR 0007). A run is stored RUNNING and carried out in the
 * background: the report's rows, one snapshot of the books, classification, then the items and the
 * summary written in one transaction that finishes the run. It only reads the books: nothing here
 * posts, reverses or changes an operation. A break is fixed through an inquiry, and a new run shows
 * the result.
 */
@Service
public class ReconEngine {

  private static final Logger log = LoggerFactory.getLogger(ReconEngine.class);

  private final ReconRuns runs;
  private final SettlementReports reports;
  private final BooksSnapshot books;
  private final TransactionTemplate transactions;
  private final ExecutorService executor;
  private final ScheduledExecutorService heartbeat;
  private final Duration heartbeatInterval;

  public ReconEngine(
      ReconRuns runs,
      SettlementReports reports,
      BooksSnapshot books,
      TransactionTemplate transactions,
      @Qualifier("reconExecutor") ExecutorService executor,
      @Qualifier("reconHeartbeat") ScheduledExecutorService heartbeat,
      ReconProperties properties) {
    this.runs = runs;
    this.reports = reports;
    this.books = books;
    this.transactions = transactions;
    this.executor = executor;
    this.heartbeat = heartbeat;
    this.heartbeatInterval = properties.heartbeatInterval();
  }

  /** Starts a run of a VALID report; the caller has checked that it is one. */
  public ReconRun start(SettlementReport report) {
    if (!report.valid()) {
      throw new IllegalArgumentException("report " + report.reportId() + " is not VALID");
    }
    UUID runId = UUID.randomUUID();
    runs.insertRunning(runId, report.reportId(), Reconciler.RULES_VERSION);
    try {
      executor.execute(() -> execute(runId, report));
    } catch (RejectedExecutionException e) {
      // Shutting down. The run is already stored, so fail it now rather than leave it looking in
      // progress until the sweeper gives up on it.
      runs.fail(runId, "not started: " + e.getMessage());
      throw e;
    }
    return runs.find(runId).orElseThrow();
  }

  void execute(UUID runId, SettlementReport report) {
    long every = heartbeatInterval.toMillis();
    ScheduledFuture<?> beating =
        heartbeat.scheduleAtFixedRate(() -> beat(runId), every, every, TimeUnit.MILLISECONDS);
    try {
      List<SettlementRow> rows = reports.rows(report.reportId());
      BooksSnapshot.Snapshot snapshot = books.read(runId, report.reportId(), report.businessDate());
      List<ReconItem> items =
          Reconciler.classify(report.businessDate(), rows, snapshot.operations());
      ReconSummary summary =
          Reconciler.summarise(report.businessDate(), rows, snapshot.operations(), items);
      boolean completed =
          Boolean.TRUE.equals(
              transactions.execute(
                  status -> {
                    if (runs.complete(runId, snapshot.takenAt(), summary, items)) {
                      return true;
                    }
                    status.setRollbackOnly();
                    return false;
                  }));
      if (completed) {
        log.info(
            "Recon run {} of report {} ({} {} v{}) completed: {} items, {}",
            runId,
            report.reportId(),
            report.psp(),
            report.businessDate(),
            report.version(),
            items.size(),
            breaks(summary));
      } else {
        log.warn("Recon run {} finished after it was given up as abandoned; result dropped", runId);
      }
    } catch (RuntimeException e) {
      log.error("Recon run {} failed", runId, e);
      runs.fail(runId, e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      beating.cancel(false);
    }
  }

  private void beat(UUID runId) {
    try {
      runs.heartbeat(runId);
    } catch (RuntimeException e) {
      // Missed beats are only a problem if they go on for abandoned-after; the next one may land.
      log.warn("Recon run {} heartbeat failed: {}", runId, e.getMessage());
    }
  }

  private static String breaks(ReconSummary summary) {
    var out = new StringBuilder();
    summary
        .categories()
        .forEach(
            (category, total) -> {
              if (category != ReconCategory.MATCHED && total.items() > 0) {
                out.append(out.isEmpty() ? "" : ", ")
                    .append(category)
                    .append('=')
                    .append(total.items());
              }
            });
    return out.isEmpty() ? "no breaks" : "breaks: " + out;
  }
}
