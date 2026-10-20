package com.ledgerpay.payment.ledger.integrity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs ledger integrity checks, keeps each result and raises the alarm (design §11.2, #19). A
 * failed check is a blocking alert: an error log naming the first violations, and the gauge {@code
 * ledger_invariant_violations}, the violation count of the latest check, which an alert rule fires
 * on when it is above zero. The gauge reads the latest stored check on every scrape, so every
 * instance reports the same check, whichever instance ran it; NaN when there is none yet. It never
 * keeps a count of its own that could go stale or be overwritten by an older read.
 */
@Service
public class LedgerIntegrity {

  static final int MAX_STORED_VIOLATIONS = 1_000;
  private static final Logger log = LoggerFactory.getLogger(LedgerIntegrity.class);

  private final LedgerIntegrityCheck check;
  private final IntegrityChecks checks;
  private final Clock clock;
  private final Counter passed;
  private final Counter failed;

  /** The last count read, reported while the database cannot be reached. */
  private volatile double lastRead = Double.NaN;

  public LedgerIntegrity(
      LedgerIntegrityCheck check, IntegrityChecks checks, Clock clock, MeterRegistry meters) {
    this.check = check;
    this.checks = checks;
    this.clock = clock;
    Gauge.builder("ledger.invariant.violations", this, LedgerIntegrity::gauge)
        .description("Violations found by the latest ledger integrity check; above 0 is an alert")
        .register(meters);
    this.passed = checksCounter(meters, IntegrityCheck.PASSED);
    this.failed = checksCounter(meters, IntegrityCheck.FAILED);
  }

  private static Counter checksCounter(MeterRegistry meters, String status) {
    return Counter.builder("ledger.integrity.checks")
        .description("Ledger integrity checks run, by result")
        .tag("status", status)
        .register(meters);
  }

  /** The latest stored check's count; if the database is unreachable, the last one read. */
  private double gauge() {
    try {
      lastRead = checks.latestViolationCount().map(Integer::doubleValue).orElse(Double.NaN);
    } catch (RuntimeException e) {
      log.warn("Could not read the latest ledger integrity check: {}", e.getMessage());
    }
    return lastRead;
  }

  public IntegrityCheck runOnDemand() {
    return run(IntegrityCheck.Trigger.ON_DEMAND, null);
  }

  public IntegrityCheck runWithReconciliation(UUID reconRunId) {
    return run(IntegrityCheck.Trigger.RECON_RUN, reconRunId);
  }

  private IntegrityCheck run(IntegrityCheck.Trigger trigger, UUID reconRunId) {
    IntegrityResult result = check.run();
    List<Violation> violations = result.violations();
    var stored =
        new IntegrityCheck(
            UUID.randomUUID(),
            trigger,
            reconRunId,
            result.passed() ? IntegrityCheck.PASSED : IntegrityCheck.FAILED,
            result.snapshotAt().truncatedTo(ChronoUnit.MICROS),
            result.journals(),
            result.postings(),
            violations.size(),
            violations.subList(0, Math.min(violations.size(), MAX_STORED_VIOLATIONS)),
            clock.instant().truncatedTo(ChronoUnit.MICROS));
    checks.insert(stored);
    if (result.passed()) {
      passed.increment();
      log.info(
          "Ledger integrity check {} passed: {} journals, {} postings",
          stored.checkId(),
          result.journals(),
          result.postings());
    } else {
      failed.increment();
      log.error(
          "LEDGER INTEGRITY CHECK {} FAILED with {} violation(s), first: {}",
          stored.checkId(),
          violations.size(),
          violations.subList(0, Math.min(violations.size(), 5)));
    }
    return stored;
  }
}
