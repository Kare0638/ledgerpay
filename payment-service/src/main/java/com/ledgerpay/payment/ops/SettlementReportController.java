package com.ledgerpay.payment.ops;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;
import com.ledgerpay.payment.api.ProblemWriter;
import com.ledgerpay.payment.api.ResourceNotFoundException;
import com.ledgerpay.payment.psp.PspProperties;
import com.ledgerpay.payment.settlement.SettlementImporter;
import com.ledgerpay.payment.settlement.SettlementProperties;
import com.ledgerpay.payment.settlement.SettlementReport;
import com.ledgerpay.payment.settlement.SettlementReports;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settlement report upload (design §7, §11.1), behind the ops key. The body is the PSP's CSV as
 * {@code text/csv}; the business day and the PSP are query parameters.
 *
 * <ul>
 *   <li>201 a new VALID version, 200 the same file again;
 *   <li>400 {@code INVALID_CSV} when any line is wrong: the file is still kept, as an INVALID
 *       version with its line errors and no rows, and the problem names it;
 *   <li>413 above the size limit, before anything is stored.
 * </ul>
 */
@RestController
@RequestMapping("/ops/settlement-reports")
class SettlementReportController {

  static final String TEXT_CSV = "text/csv";
  private static final Pattern PSP = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

  private final SettlementImporter importer;
  private final SettlementReports reports;
  private final long maxFileSize;
  private final String defaultPsp;

  SettlementReportController(
      SettlementImporter importer,
      SettlementReports reports,
      SettlementProperties settlement,
      PspProperties psp) {
    this.importer = importer;
    this.reports = reports;
    this.maxFileSize = settlement.maxFileSize().toBytes();
    this.defaultPsp = psp.provider();
  }

  @PostMapping(consumes = TEXT_CSV)
  ResponseEntity<Object> upload(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
      @RequestParam(required = false) String psp,
      HttpServletRequest request)
      throws IOException {
    String provider = psp(psp);
    byte[] content = read(request);
    var imported = importer.importReport(provider, businessDate, content);
    SettlementReport report = imported.report();
    if (!report.valid()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(invalid(report));
    }
    if (!imported.created()) {
      return ResponseEntity.ok(report);
    }
    return ResponseEntity.created(URI.create("/ops/settlement-reports/" + report.reportId()))
        .body(report);
  }

  @GetMapping("/{id}")
  SettlementReport get(@PathVariable UUID id) {
    return reports
        .find(id)
        .orElseThrow(() -> new ResourceNotFoundException("Settlement report " + id));
  }

  /** Every version uploaded for the day, oldest first. */
  @GetMapping
  List<SettlementReport> versions(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
      @RequestParam(required = false) String psp) {
    return reports.versions(psp(psp), businessDate);
  }

  private String psp(String psp) {
    if (psp == null) {
      return defaultPsp;
    }
    if (!PSP.matcher(psp).matches()) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          "psp must be lower-case letters, digits, '-' or '_', up to 64 characters");
    }
    return psp;
  }

  /** The body, up to the limit; one byte more and the upload is refused without being kept. */
  private byte[] read(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > maxFileSize) {
      throw tooLarge();
    }
    try (InputStream in = request.getInputStream()) {
      byte[] content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxFileSize + 1));
      if (content.length > maxFileSize) {
        throw tooLarge();
      }
      return content;
    }
  }

  private ApiException tooLarge() {
    return new ApiException(
        ErrorCode.PAYLOAD_TOO_LARGE,
        "Settlement reports are limited to " + maxFileSize + " bytes; split the file");
  }

  private static ProblemDetail invalid(SettlementReport report) {
    ProblemDetail problem =
        ProblemWriter.problem(
            ErrorCode.INVALID_CSV,
            "Settlement report has "
                + report.errorCount()
                + (report.errorCount() == 1 ? " error" : " errors")
                + "; it is kept as INVALID version "
                + report.version()
                + " and will not be reconciled");
    problem.setProperty("reportId", report.reportId());
    problem.setProperty("version", report.version());
    problem.setProperty("rowCount", report.rowCount());
    problem.setProperty("errorCount", report.errorCount());
    problem.setProperty("errors", report.errors());
    return problem;
  }
}
