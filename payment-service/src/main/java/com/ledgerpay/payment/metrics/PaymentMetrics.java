package com.ledgerpay.payment.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The service's event metrics (design §13): what happened, counted where it happens. The state the
 * database holds, such as what is pending, is in {@link OperationalGauges}.
 *
 * <p>Names are Micrometer's dotted form; Prometheus shows {@code payments.total} as {@code
 * payments_total}. Labels take only a few fixed values, never IDs.
 */
@Component
public class PaymentMetrics {

  private final MeterRegistry meters;

  public PaymentMetrics(MeterRegistry meters) {
    this.meters = meters;
    // Registered at 0, so dashboards and alert expressions find a series before the first event.
    meters.counter("idempotency.replays");
    meters.counter("inbox.quarantined");
    meters.counter("webhook.signature.failures");
    // Without these, the first failed run after a start would create the series at 1, and
    // increase() over it would be 0: the alert would miss it.
    meters.counter("recon.runs.failed");
    reconRunTimer("completed");
    reconRunTimer("failed");
    for (String operation : new String[] {"authorize", "capture", "void", "refund"}) {
      for (String outcome : new String[] {"succeeded", "failed"}) {
        meters.counter("payments", "operation", operation, "outcome", outcome);
      }
    }
  }

  /**
   * A PSP outcome applied by the money transaction: {@code payments_total{operation, outcome}}.
   * Counted once the transaction commits, so a rolled-back one is not counted, and a duplicate that
   * changes nothing never gets here.
   */
  public void outcomeApplied(String operation, boolean succeeded) {
    Runnable count =
        () ->
            meters
                .counter(
                    "payments",
                    "operation",
                    operation.toLowerCase(Locale.ROOT),
                    "outcome",
                    succeeded ? "succeeded" : "failed")
                .increment();
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              count.run();
            }
          });
    } else {
      count.run();
    }
  }

  /** A request answered from its stored response: {@code idempotency_replays_total}. */
  public void idempotencyReplay() {
    meters.counter("idempotency.replays").increment();
  }

  /**
   * One PSP call, with how it ended: {@code psp_call_duration_seconds{operation, outcome}}; an
   * unknown outcome also counts in {@code psp_call_errors_total{operation}}.
   *
   * @param outcome accepted, final or unknown
   */
  public void pspCall(String operation, String outcome, Duration took) {
    String type = operation.toLowerCase(Locale.ROOT);
    Timer.builder("psp.call.duration")
        .description("PSP calls by operation and how they ended")
        .tag("operation", type)
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(meters)
        .record(took);
    if (outcome.equals("unknown")) {
      meters.counter("psp.call.errors", "operation", type).increment();
    }
  }

  /** A webhook set aside for a human: {@code inbox_quarantined_total}. */
  public void inboxQuarantined() {
    meters.counter("inbox.quarantined").increment();
  }

  /** A webhook refused for its signature or timestamp: {@code webhook_signature_failures_total}. */
  public void webhookSignatureFailure() {
    meters.counter("webhook.signature.failures").increment();
  }

  /**
   * How long a run this process carried out took, by how it ended: {@code
   * recon_run_duration_seconds{status}}. A run the sweeper fails never gets here; {@link
   * #reconRunsFailed} counts failures for alerting.
   */
  public void reconRun(String status, Duration took) {
    reconRunTimer(status.toLowerCase(Locale.ROOT)).record(took);
  }

  /**
   * Runs that became FAILED, wherever that happened: an exception while running, a start the
   * executor refused, or the sweeper giving up on a dead one. {@code recon_runs_failed_total}, the
   * alert's input, so it matches the FAILED runs in recon_runs.
   */
  public void reconRunsFailed(int runs) {
    meters.counter("recon.runs.failed").increment(runs);
  }

  private Timer reconRunTimer(String status) {
    return Timer.builder("recon.run.duration")
        .description("Reconciliation runs carried out, by how they ended")
        .tag("status", status)
        .register(meters);
  }
}
