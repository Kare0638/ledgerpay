package com.ledgerpay.payment.settlement;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports a PSP's settlement CSV (design §11.1). The same file again (by SHA-256) is the report
 * already stored. Any other file becomes the day's next version, VALID with its rows or INVALID
 * with its line errors and no rows; earlier versions stay as they are.
 */
@Service
public class SettlementImporter {

  private static final Logger log = LoggerFactory.getLogger(SettlementImporter.class);

  /** The report, and whether this upload stored it or found it already there. */
  public record Imported(SettlementReport report, boolean created) {}

  private final SettlementReports reports;
  private final SettlementCsvParser parser;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Set<String> formulaEscapingPsps;

  public SettlementImporter(
      SettlementReports reports,
      SettlementCsvParser parser,
      TransactionTemplate transactions,
      Clock clock,
      SettlementProperties properties) {
    this.reports = reports;
    this.parser = parser;
    this.transactions = transactions;
    this.clock = clock;
    this.formulaEscapingPsps = properties.formulaEscapingPsps();
  }

  public Imported importReport(String psp, LocalDate businessDate, byte[] content) {
    String sha256 = sha256(content);
    Optional<SettlementReport> existing = reports.findByFile(psp, businessDate, sha256);
    if (existing.isPresent()) {
      return new Imported(existing.get(), false);
    }
    // Parsed outside the transaction: up to 100,000 rows of work need no lock.
    SettlementCsvParser.Result parsed =
        parser.parse(content, businessDate, formulaEscapingPsps.contains(psp));
    Imported imported =
        transactions.execute(
            status -> {
              reports.lockDay(psp, businessDate);
              Optional<SettlementReport> raced = reports.findByFile(psp, businessDate, sha256);
              if (raced.isPresent()) {
                return new Imported(raced.get(), false);
              }
              var report =
                  new SettlementReport(
                      UUID.randomUUID(),
                      psp,
                      businessDate,
                      reports.nextVersion(psp, businessDate),
                      parsed.valid() ? SettlementReport.VALID : SettlementReport.INVALID,
                      parsed.rowCount(),
                      parsed.errorCount(),
                      parsed.errors(),
                      sha256,
                      // PostgreSQL keeps microseconds: the response must match what is stored.
                      clock.instant().truncatedTo(ChronoUnit.MICROS));
              reports.insert(report, parsed.rows());
              return new Imported(report, true);
            });
    if (imported.created()) {
      SettlementReport report = imported.report();
      log.info(
          "Settlement report {} for {} {} stored as version {}: {}, {} rows, {} errors",
          report.reportId(),
          psp,
          businessDate,
          report.version(),
          report.status(),
          report.rowCount(),
          report.errorCount());
    }
    return imported;
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
