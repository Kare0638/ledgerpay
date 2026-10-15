package com.ledgerpay.mockpsp.report;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /reports/settlement?date=YYYY-MM-DD}: the day's settlement CSV (design §9.4, §11.1). A
 * day that has not ended yet gives the rows so far; a real PSP would publish after the cut-off.
 */
@RestController
class SettlementReportController {

  static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  private final SettlementReport report;

  SettlementReportController(SettlementReport report) {
    this.report = report;
  }

  @GetMapping("/reports/settlement")
  ResponseEntity<String> settlement(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return ResponseEntity.ok()
        .contentType(TEXT_CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("settlement-mock-psp-" + date + ".csv")
                .build()
                .toString())
        .body(report.csv(date));
  }
}
