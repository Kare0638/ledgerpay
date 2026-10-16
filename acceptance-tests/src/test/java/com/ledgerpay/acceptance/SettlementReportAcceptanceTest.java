package com.ledgerpay.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerpay.common.csv.Csv;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The PSP's view of a day, from mock-psp's own records into payment-service (design §11.1): the
 * real export imports as a VALID report with every row, and a damaged copy is rejected whole
 * (AT-16), kept as an INVALID version with line errors and no rows, which cannot be reconciled.
 */
class SettlementReportAcceptanceTest extends AcceptanceTestSupport {

  record Upload(int status, JsonNode body) {}

  /** A captured £100 payment with a £30 refund; returns the payment. */
  UUID capturedAndRefunded() throws Exception {
    UUID paymentId = authorised();
    post("/v1/payments/" + paymentId + "/capture", null);
    until(paymentId, "CAPTURED");
    JsonNode refund =
        post(
            "/v1/payments/" + paymentId + "/refunds",
            """
            {"merchantReference": "refund-%s", "amountMinor": 3000, "reason": "returned"}"""
                .formatted(UUID.randomUUID()));
    String refundId = refund.path("refundId").asText();
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              worker.drain();
              inbox.processAll();
              return jdbc.sql("SELECT status FROM refunds WHERE id = ?::uuid")
                  .param(refundId)
                  .query(String.class)
                  .single()
                  .equals("SUCCEEDED");
            });
    return paymentId;
  }

  /** Our capture and refund as payment-service knows them: PSP reference to request ID. */
  List<Map<String, Object>> localOperations(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT type, psp_reference, psp_request_id FROM psp_operations
             WHERE payment_id = ? AND type IN ('CAPTURE', 'REFUND') ORDER BY type""")
        .param(paymentId)
        .query()
        .listOfRows();
  }

  Upload upload(String psp, LocalDate day, String csv) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        LiveStack.paymentUrl()
                            + "/ops/settlement-reports?businessDate=%s&psp=%s".formatted(day, psp)))
                .header("Authorization", "Bearer " + LiveStack.OPS_KEY)
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(csv))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Upload(response.statusCode(), json.readTree(response.body()));
  }

  long storedRows(String reportId) {
    return jdbc.sql("SELECT count(*) FROM settlement_rows WHERE report_id = ?::uuid")
        .param(reportId)
        .query(Long.class)
        .single();
  }

  /** The export with one change applied to every record (the header included). */
  static String rewrite(String csv, UnaryOperator<List<String>> change) throws Exception {
    var out = new StringBuilder();
    for (Csv.Record record : Csv.parse(csv)) {
      out.append(Csv.row(change.apply(new ArrayList<>(record.cells()))));
    }
    return out.toString();
  }

  @Test
  void theDaysExportImportsWithEveryRowAndADamagedCopyIsRejectedWhole() throws Exception {
    UUID paymentId = capturedAndRefunded();
    List<Map<String, Object>> local = localOperations(paymentId);
    String capture = (String) local.get(0).get("psp_reference");
    String refund = (String) local.get(1).get("psp_reference");
    LocalDate day = successDay(capture);
    String psp = "mock-psp-at16-" + UUID.randomUUID().toString().substring(0, 8);

    String report = export(day);
    assertThat(report).contains(capture + "," + local.get(0).get("psp_request_id") + ",CAPTURE,");

    Upload valid = upload(psp, day, report);
    assertThat(valid.status()).as(valid.body().toString()).isEqualTo(201);
    assertThat(valid.body().path("status").asText()).isEqualTo("VALID");
    String validId = valid.body().path("reportId").asText();
    int rows = valid.body().path("rowCount").asInt();
    assertThat(storedRows(validId)).isEqualTo(rows).isEqualTo(report.lines().count() - 1);
    List<String> ours =
        jdbc.sql(
                """
                SELECT psp_reference || ':' || txn_type || ':' || amount_minor FROM settlement_rows
                 WHERE report_id = ?::uuid AND merchant_id = ? ORDER BY line_no""")
            .params(validId, merchant)
            .query(String.class)
            .list();
    // The refund is in the same day's report unless the test ran across midnight UTC.
    assertThat(ours)
        .isEqualTo(
            successDay(refund).equals(day)
                ? List.of(capture + ":CAPTURE:10000", refund + ":REFUND:3000")
                : List.of(capture + ":CAPTURE:10000"));

    Upload again = upload(psp, day, report);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("reportId").asText()).isEqualTo(validId);

    // AT-16: a bad amount, a missing column and a row from another day each make it INVALID.
    String badAmount =
        rewrite(
            report,
            cells -> {
              if (cells.getFirst().equals(capture)) {
                cells.set(4, "100.00");
              }
              return cells;
            });
    String missingColumn =
        rewrite(
            report,
            cells -> {
              cells.remove(5);
              return cells;
            });
    String wrongDate =
        rewrite(
            report,
            cells -> {
              if (cells.getFirst().equals(capture)) {
                cells.set(6, day.minusDays(1) + cells.get(6).substring(10));
              }
              return cells;
            });

    List<String> lines = report.lines().toList();
    int captureLine =
        1
            + IntStream.range(0, lines.size())
                .filter(i -> lines.get(i).startsWith(capture + ","))
                .findFirst()
                .orElseThrow();
    int version = 1;
    for (var damaged :
        List.of(
            Map.entry(badAmount, "amount_minor"),
            Map.entry(missingColumn, "currency"),
            Map.entry(wrongDate, "succeeded_at"))) {
      Upload invalid = upload(psp, day, damaged.getKey());

      assertThat(invalid.status()).as(damaged.getValue()).isEqualTo(400);
      JsonNode problem = invalid.body();
      assertThat(problem.path("code").asText()).isEqualTo("INVALID_CSV");
      assertThat(problem.path("version").asInt()).isEqualTo(++version);
      JsonNode error = problem.path("errors").get(0);
      assertThat(error.path("column").asText()).isEqualTo(damaged.getValue());
      assertThat(error.path("line").asInt())
          .isEqualTo(damaged.getValue().equals("currency") ? 1 : captureLine);
      assertThat(storedRows(problem.path("reportId").asText())).isZero();
      // ...and no reconciliation result can come from it.
      String invalidId = problem.path("reportId").asText();
      assertThat(
              ops(
                      "POST",
                      "/ops/recon-runs",
                      "application/json",
                      "{\"reportId\": \"" + invalidId + "\"}")
                  .statusCode())
          .isEqualTo(409);
      assertThat(ops("GET", "/ops/recon-runs?reportId=" + invalidId, null, null).body())
          .isEqualTo("[]");
    }

    // The valid version is untouched, with all its rows.
    assertThat(storedRows(validId)).isEqualTo(rows);
  }
}
