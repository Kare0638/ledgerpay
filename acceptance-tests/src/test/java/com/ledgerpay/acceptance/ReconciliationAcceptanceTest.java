package com.ledgerpay.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AT-15 (design §11, ADR 0007): mock-psp captures a payment but its webhook is lost. Reconciling
 * the day's report finds the capture as a STATUS_MISMATCH; an inquiry books it through the normal
 * path, once; a new run of the same report is all MATCHED for it, and the first run is unchanged.
 */
class ReconciliationAcceptanceTest extends AcceptanceTestSupport {

  JsonNode body(HttpResponse<String> response, int status) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return json.readTree(response.body());
  }

  JsonNode run(UUID reportId) throws Exception {
    String runId =
        body(
                ops(
                    "POST",
                    "/ops/recon-runs",
                    "application/json",
                    "{\"reportId\": \"" + reportId + "\"}"),
                202)
            .path("runId")
            .asText();
    JsonNode run =
        await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(Duration.ofMillis(100))
            .until(
                () -> body(ops("GET", "/ops/recon-runs/" + runId, null, null), 200),
                r -> !r.path("status").asText().equals("RUNNING"));
    assertThat(run.path("status").asText()).as(run.toString()).isEqualTo("COMPLETED");
    return run;
  }

  /** The run's item for this PSP reference. */
  JsonNode item(JsonNode run, String pspReference) throws Exception {
    JsonNode page =
        body(
            ops(
                "GET",
                "/ops/recon-runs/" + run.path("runId").asText() + "/items?limit=1000",
                null,
                null),
            200);
    for (JsonNode item : page.path("items")) {
      if (item.path("pspReference").asText().equals(pspReference)) {
        return item;
      }
    }
    throw new AssertionError("no item for " + pspReference + " in " + page);
  }

  Map<String, Object> captureOperation(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT id, psp_reference, status FROM psp_operations
             WHERE payment_id = ? AND type = 'CAPTURE'""")
        .param(paymentId)
        .query()
        .singleRow();
  }

  @Test
  void aLostWebhookIsFoundByReconciliationAndFixedByAnInquiry() throws Exception {
    UUID paymentId = authorised();
    LiveStack.FAULTS.inject(merchant, "CAPTURE", "DROP_WEBHOOK", 1);
    post("/v1/payments/" + paymentId + "/capture", null);
    worker.drain();
    String reference = (String) captureOperation(paymentId).get("psp_reference");
    assertThat(reference).as("mock-psp accepted the capture").isNotNull();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                mockPsp
                    .sql("SELECT status FROM operations WHERE psp_reference = ?")
                    .param(reference)
                    .query(String.class)
                    .single()
                    .equals("SUCCEEDED"));
    inbox.processAll();
    assertThat(status(paymentId)).isEqualTo("CAPTURE_PENDING");

    LocalDate day = successDay(reference);
    String psp = "mock-psp-at15-" + UUID.randomUUID().toString().substring(0, 8);
    UUID reportId =
        UUID.fromString(
            body(
                    ops(
                        "POST",
                        "/ops/settlement-reports?businessDate=" + day + "&psp=" + psp,
                        "text/csv",
                        export(day)),
                    201)
                .path("reportId")
                .asText());

    JsonNode first = run(reportId);
    JsonNode found = item(first, reference);
    assertThat(found.path("category").asText()).isEqualTo("STATUS_MISMATCH");
    assertThat(found.path("internal").path("status").asText()).isEqualTo("PENDING");
    assertThat(found.path("externalLines").get(0).path("amountMinor").asLong()).isEqualTo(10000);
    assertThat(journals(paymentId)).as("reconciliation books nothing").isZero();

    // The fix goes through the normal path: an inquiry, then the money transaction.
    String operationId = found.path("pspOperationId").asText();
    body(ops("POST", "/ops/psp-operations/" + operationId + "/inquiry", null, null), 202);
    until(paymentId, "CAPTURED");
    assertThat(journals(paymentId)).isEqualTo(1);
    assertThat(capturedMinor(paymentId)).isEqualTo(10000);

    JsonNode second = run(reportId);
    // Every journal here came through the money transaction: the books agree with themselves on
    // both runs, before the fix and after it (#19).
    assertThat(first.path("ledgerIntegrity").path("status").asText()).isEqualTo("PASSED");
    assertThat(second.path("ledgerIntegrity").path("status").asText()).isEqualTo("PASSED");
    assertThat(item(second, reference).path("category").asText()).isEqualTo("MATCHED");

    JsonNode firstAgain =
        body(ops("GET", "/ops/recon-runs/" + first.path("runId").asText(), null, null), 200);
    assertThat(firstAgain).isEqualTo(first);
    assertThat(item(firstAgain, reference).path("category").asText()).isEqualTo("STATUS_MISMATCH");
    assertThat(
            body(ops("GET", "/ops/recon-runs?reportId=" + reportId, null, null), 200)
                .findValuesAsText("runId"))
        .containsExactly(first.path("runId").asText(), second.path("runId").asText());
    assertThat(
            mockPsp
                .sql("SELECT count(*) FROM operations WHERE merchant_id = ? AND type = 'CAPTURE'")
                .param(merchant)
                .query(Long.class)
                .single())
        .as("mock-psp still has one capture")
        .isEqualTo(1);
  }
}
