package com.ledgerpay.mockpsp.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.mockpsp.PostgresTestcontainersConfiguration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The settlement report (design §11.1): successful captures and refunds of one UTC day, from
 * mock-psp's own records. Each test uses its own day in 2001, which no other test touches.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "mockpsp.scheduling.enabled=false")
@Import(PostgresTestcontainersConfiguration.class)
class SettlementReportTest {

  static final String HEADER =
      "psp_reference,psp_request_id,txn_type,merchant_id,amount_minor,currency,succeeded_at\r\n";

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;

  /** Inserts an operation as it would be once final; returns its psp_reference. */
  String operation(
      String merchant, String type, String parent, String status, long amount, String succeeded) {
    String reference = "psp_" + UUID.randomUUID();
    Instant at =
        succeeded == null ? Instant.parse("2001-01-01T00:00:00Z") : Instant.parse(succeeded);
    jdbc.sql(
            """
            INSERT INTO operations (psp_reference, psp_request_id, merchant_id, type,
                                    parent_reference, amount_minor, currency, status,
                                    failure_reason, settle_at, succeeded_at)
            VALUES (?, ?, ?, ?, ?, ?, 'GBP', ?, ?, ?, ?)""")
        .params(
            reference,
            "req_" + UUID.randomUUID(),
            merchant,
            type,
            parent,
            amount,
            status,
            status.equals("FAILED") ? "DECLINED" : null,
            at.atOffset(ZoneOffset.UTC),
            status.equals("SUCCEEDED") ? at.atOffset(ZoneOffset.UTC) : null)
        .update();
    return reference;
  }

  String succeeded(String type, String parent, long amount, String at) {
    return operation("m_report", type, parent, "SUCCEEDED", amount, at);
  }

  String requestId(String reference) {
    return jdbc.sql("SELECT psp_request_id FROM operations WHERE psp_reference = ?")
        .param(reference)
        .query(String.class)
        .single();
  }

  String report(String date) {
    var response = rest.getForEntity("/reports/settlement?date=" + date, String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return response.getBody();
  }

  @Test
  void listsTheDaysSuccessfulCapturesAndRefundsInSuccessOrder() {
    String auth = succeeded("AUTHORIZE", null, 10000, "2001-02-03T09:00:00Z");
    String capture = succeeded("CAPTURE", auth, 10000, "2001-02-03T10:00:00.5Z");
    String refund = succeeded("REFUND", capture, 3000, "2001-02-03T11:00:00Z");
    String voidAuth = succeeded("AUTHORIZE", null, 500, "2001-02-03T09:30:00Z");
    succeeded("VOID", voidAuth, 500, "2001-02-03T09:45:00Z");
    operation("m_report", "CAPTURE", voidAuth, "FAILED", 500, null);
    operation("m_report", "REFUND", capture, "PENDING", 100, null);

    assertThat(report("2001-02-03"))
        .isEqualTo(
            HEADER
                + "%s,%s,CAPTURE,m_report,10000,GBP,2001-02-03T10:00:00.500Z\r\n"
                    .formatted(capture, requestId(capture))
                + "%s,%s,REFUND,m_report,3000,GBP,2001-02-03T11:00:00Z\r\n"
                    .formatted(refund, requestId(refund)));
  }

  @Test
  void theBusinessDayIsTheUtcDayIncludingItsStartAndExcludingTheNextOne() {
    String auth = succeeded("AUTHORIZE", null, 100, "2001-03-01T00:00:00Z");
    String before = succeeded("CAPTURE", auth, 100, "2001-03-03T23:59:59.999999Z");
    String first = succeeded("REFUND", before, 1, "2001-03-04T00:00:00Z");
    String last = succeeded("REFUND", before, 2, "2001-03-04T23:59:59.999999Z");
    String after = succeeded("REFUND", before, 3, "2001-03-05T00:00:00Z");

    String report = report("2001-03-04");

    assertThat(report.lines().skip(1).map(line -> line.split(",")[0])).containsExactly(first, last);
    assertThat(report).contains("2001-03-04T23:59:59.999999Z").doesNotContain(before, after);
  }

  @Test
  void aDayWithoutMoneyMovementIsJustTheHeader() {
    assertThat(report("2001-04-01")).isEqualTo(HEADER);
  }

  @Test
  void cellsThatASpreadsheetWouldRunAsFormulasAreEscaped() {
    String auth =
        operation("=cmd|'/C calc'!A0", "AUTHORIZE", null, "SUCCEEDED", 100, "2001-05-01T08:00:00Z");
    operation("=cmd|'/C calc'!A0", "CAPTURE", auth, "SUCCEEDED", 100, "2001-05-01T09:00:00Z");

    assertThat(report("2001-05-01")).contains(",CAPTURE,'=cmd|'/C calc'!A0,100,");
  }

  @Test
  void isServedAsACsvAttachmentNamedForTheDay() {
    var response = rest.getForEntity("/reports/settlement?date=2001-04-02", String.class);

    assertThat(response.getHeaders().getContentType().toString())
        .isEqualTo("text/csv;charset=UTF-8");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"settlement-mock-psp-2001-04-02.csv\"");
  }

  @Test
  void aMissingOrInvalidDateIsABadRequest() throws Exception {
    for (String query : new String[] {"", "?date=2001-02-30", "?date=03/02/2001"}) {
      var response = rest.getForEntity("/reports/settlement" + query, String.class);

      assertThat(response.getStatusCode().value()).as(query).isEqualTo(400);
      assertThat(json.readTree(response.getBody()).path("code").asText())
          .isEqualTo("INVALID_REQUEST");
    }
  }
}
