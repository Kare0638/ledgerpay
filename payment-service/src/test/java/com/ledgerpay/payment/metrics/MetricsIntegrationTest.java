package com.ledgerpay.payment.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.TestDays;
import com.ledgerpay.payment.api.ApiKeys;
import com.ledgerpay.payment.ledger.integrity.LedgerIntegrity;
import com.ledgerpay.payment.psp.PspOperationWorker;
import com.ledgerpay.payment.psp.StubPsp;
import com.ledgerpay.payment.psp.StubPsp.Reply;
import com.ledgerpay.payment.reconciliation.BooksSnapshot;
import com.ledgerpay.payment.reconciliation.ReconEngine;
import com.ledgerpay.payment.reconciliation.ReconProperties;
import com.ledgerpay.payment.reconciliation.ReconRuns;
import com.ledgerpay.payment.settlement.SettlementReports;
import com.ledgerpay.payment.webhook.InboxProcessor;
import com.ledgerpay.payment.webhook.SignedWebhooks;
import com.ledgerpay.payment.webhook.SignedWebhooks.Event;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The design's metrics (§13) on {@code /actuator/prometheus}, each moved by what it counts, and the
 * secrets a request carries absent from the logs. A context of its own, so its counters start at 0.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledgerpay.psp.webhook-secret=" + SignedWebhooks.SECRET,
      "ledgerpay.ops.api-key-hash=" + MetricsIntegrationTest.OPS_KEY_HASH,
      "ledgerpay.recon.sweeper.enabled=false"
    })
@AutoConfigureObservability(tracing = false)
@Import(PostgresTestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MetricsIntegrationTest {

  static final String OPS_KEY = "ops_metrics_key_6d1f";
  // ApiKeys.hash(OPS_KEY)
  static final String OPS_KEY_HASH =
      "3e403c0f38cfcff86dfb134008739bdd2d914530ed0b0c397878cdb9de1a63d2";

  static final StubPsp psp = new StubPsp();

  @DynamicPropertySource
  static void pspUrl(DynamicPropertyRegistry registry) {
    registry.add("ledgerpay.psp.base-url", psp::url);
  }

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired PspOperationWorker worker;
  @Autowired InboxProcessor inbox;
  @Autowired OperationalGauges gauges;

  String merchant;
  String apiKey;

  @BeforeEach
  void merchant() {
    psp.reset();
    // Other test classes' operations are not this test's to call.
    jdbc.sql("UPDATE psp_operations SET next_attempt_at = now() + interval '1 day'").update();
    merchant = "m_" + UUID.randomUUID();
    apiKey = "mk_secret_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, ApiKeys.hash(apiKey))
        .update();
  }

  String scrape() {
    gauges.refresh();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(OPS_KEY); // metrics are behind the ops key
    var response =
        rest.exchange(
            "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(response.getStatusCode().value())
        .as(String.valueOf(response.getBody()))
        .isEqualTo(200);
    return response.getBody();
  }

  /** The value of one series, by its full name and labels as Prometheus prints them. */
  double value(String scrape, String series) {
    return scrape
        .lines()
        .filter(line -> line.startsWith(series + " "))
        .map(line -> Double.parseDouble(line.substring(series.length() + 1)))
        .findFirst()
        .orElse(Double.NaN);
  }

  String create(String key, long amount) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(apiKey);
    headers.set("Idempotency-Key", key);
    var response =
        rest.exchange(
            "/v1/payments",
            HttpMethod.POST,
            new HttpEntity<>(
                """
                {"merchantReference": "%s", "amountMinor": %d, "currency": "GBP"}"""
                    .formatted(key, amount),
                headers),
            String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(202);
    try {
      return json.readTree(response.getBody()).path("paymentId").asText();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  String requestId(String paymentId) {
    return jdbc.sql("SELECT psp_request_id FROM psp_operations WHERE payment_id = ?::uuid")
        .param(paymentId)
        .query(String.class)
        .single();
  }

  @Test
  void eachEventMovesItsMetric(CapturedOutput output) {
    String key = "order-" + UUID.randomUUID();
    String payment = create(key, 10_000);
    create(key, 10_000); // the same request again: replayed

    psp.respond(
        r ->
            Reply.of(
                201,
                StubPsp.operation(requestId(payment), "psp_" + requestId(payment), "PENDING")));
    worker.runOnce();

    Event authorised = Event.of("AUTHORIZE", requestId(payment), merchant, 10_000, "SUCCEEDED");
    SignedWebhooks.send(rest, authorised.json());
    SignedWebhooks.send(rest, authorised.json()); // a duplicate changes nothing, counts nothing
    SignedWebhooks.send(rest, authorised.json(), "1", "bad-signature");
    SignedWebhooks.send(
        rest,
        Event.of("CAPTURE", "req_unknown_" + UUID.randomUUID(), merchant, 1, "SUCCEEDED").json());
    inbox.processAll();

    String scrape = scrape();
    assertThat(
            value(
                scrape,
                "payments_total{application=\"payment-service\",operation=\"authorize\",outcome=\"succeeded\"}"))
        .isEqualTo(1);
    assertThat(value(scrape, "idempotency_replays_total{application=\"payment-service\"}"))
        .isEqualTo(1);
    assertThat(value(scrape, "webhook_signature_failures_total{application=\"payment-service\"}"))
        .isEqualTo(1);
    assertThat(value(scrape, "inbox_quarantined_total{application=\"payment-service\"}"))
        .isEqualTo(1);
    assertThat(value(scrape, "inbox_quarantined_events{application=\"payment-service\"}"))
        .isGreaterThanOrEqualTo(1);
    assertThat(
            value(
                scrape,
                "psp_call_duration_seconds_count{application=\"payment-service\",operation=\"authorize\",outcome=\"accepted\"}"))
        .isGreaterThanOrEqualTo(1);

    // The secrets the requests carried are nowhere in the logs.
    assertThat(output.getAll()).doesNotContain(apiKey, SignedWebhooks.SECRET, OPS_KEY);
  }

  @Test
  void aPspCallWithoutAnAnswerCountsAsAnError() {
    String payment = create("order-" + UUID.randomUUID(), 500);
    psp.respond(r -> Reply.of(503, "{\"code\": \"UNAVAILABLE\"}"));

    worker.runOnce();

    String scrape = scrape();
    assertThat(
            value(
                scrape,
                "psp_call_errors_total{application=\"payment-service\",operation=\"authorize\"}"))
        .isGreaterThanOrEqualTo(1);
    assertThat(value(scrape, "psp_operations_pending{application=\"payment-service\"}"))
        .isGreaterThanOrEqualTo(1);
    assertThat(requestId(payment)).isNotBlank();
  }

  @Test
  void theDatabaseGaugesAreAllThereAndReadTogether() {
    String scrape = scrape();

    for (String gauge :
        new String[] {
          "psp_operations_pending",
          "psp_operation_oldest_pending_seconds",
          "psp_operations_needs_review",
          "inbox_quarantined_events",
          "outbox_unpublished_events",
          "outbox_publish_lag_seconds",
          "outbox_quarantined_events",
          "refund_reserved_minor",
          "ledger_invariant_violations"
        }) {
      assertThat(scrape)
          .as(gauge)
          .containsPattern("(?m)^" + gauge + "\\{application=\"payment-service\"\\} ");
    }
    for (String category :
        new String[] {
          "MATCHED",
          "DUPLICATE_EXTERNAL",
          "MISSING_INTERNAL",
          "MISSING_EXTERNAL",
          "AMOUNT_MISMATCH",
          "STATUS_MISMATCH"
        }) {
      assertThat(scrape)
          .contains("recon_breaks{application=\"payment-service\",category=\"" + category + "\"}");
    }
    assertThat(scrape).contains("http_server_requests_seconds_bucket");
  }

  @Test
  void theOpsKeyIsNotLoggedEither(CapturedOutput output) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(OPS_KEY);
    rest.exchange(
        "/ops/ledger-integrity-checks", HttpMethod.POST, new HttpEntity<>(headers), String.class);
    headers.setBearerAuth("ops_wrong_" + OPS_KEY);
    rest.exchange(
        "/ops/ledger-integrity-checks", HttpMethod.POST, new HttpEntity<>(headers), String.class);

    assertThat(output.getAll()).doesNotContain(OPS_KEY);
  }

  /** A settlement report and a completed run of it with these items per category. */
  void completedRun(String psp, LocalDate day, int version, Map<String, Integer> items) {
    UUID report = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO settlement_reports (id, psp, business_date, file_sha256, version, status,
                                            row_count)
            VALUES (?, ?, ?, ?, ?, 'VALID', 0)""")
        .params(report, psp, day, "sha_" + UUID.randomUUID(), version)
        .update();
    var categories = json.createObjectNode();
    items.forEach((category, count) -> categories.putObject(category).put("items", count));
    var summary = json.createObjectNode();
    summary.set("categories", categories);
    jdbc.sql(
            """
            INSERT INTO recon_runs (id, report_id, rules_version, status, snapshot_at, summary,
                                    finished_at)
            VALUES (?, ?, '1', 'COMPLETED', now(), ?::jsonb, clock_timestamp())""")
        .params(UUID.randomUUID(), report, summary.toString())
        .update();
  }

  double statusMismatches() {
    return value(
        scrape(), "recon_breaks{application=\"payment-service\",category=\"STATUS_MISMATCH\"}");
  }

  /**
   * Breaks still open on one day are not hidden by a clean run of another day or another PSP; only
   * a new run of that same day clears them.
   */
  @Test
  void openBreaksOfOneDayStayVisibleWhateverRunsAfter() {
    String psp = "psp-" + UUID.randomUUID().toString().substring(0, 8);
    LocalDate day = TestDays.next();
    double before = statusMismatches();

    completedRun(psp, day, 1, Map.of("MATCHED", 3, "STATUS_MISMATCH", 2));
    assertThat(statusMismatches()).isEqualTo(before + 2);

    completedRun(psp, day.minusDays(1), 1, Map.of("MATCHED", 5));
    completedRun("other-" + psp, day, 1, Map.of("MATCHED", 4));
    assertThat(statusMismatches()).as("other day and PSP clean").isEqualTo(before + 2);

    completedRun(psp, day, 2, Map.of("MATCHED", 5));
    assertThat(statusMismatches()).as("the same day run again").isEqualTo(before);
  }

  @Autowired ReconRuns runs;
  @Autowired SettlementReports settlementReports;
  @Autowired BooksSnapshot books;
  @Autowired TransactionTemplate transactions;
  @Autowired LedgerIntegrity integrity;
  @Autowired PaymentMetrics metrics;

  /** A run counts as failed once, where it becomes FAILED: here, a start the executor refuses. */
  @Test
  void aRunThatBecomesFailedIsCountedOnce() throws Exception {
    String psp = "psp-" + UUID.randomUUID().toString().substring(0, 8);
    UUID report = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO settlement_reports (id, psp, business_date, file_sha256, version, status,
                                            row_count)
            VALUES (?, ?, ?, 'sha', 1, 'VALID', 0)""")
        .params(report, psp, TestDays.next())
        .update();
    ExecutorService closed = Executors.newVirtualThreadPerTaskExecutor();
    closed.close();
    ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    double before = value(scrape(), "recon_runs_failed_total{application=\"payment-service\"}");
    try {
      var engine =
          new ReconEngine(
              runs,
              settlementReports,
              books,
              transactions,
              closed,
              heartbeat,
              new ReconProperties(Duration.ofMinutes(10), Duration.ofMinutes(1)),
              integrity,
              metrics);
      assertThatThrownBy(() -> engine.start(settlementReports.find(report).orElseThrow()))
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      heartbeat.shutdownNow();
    }
    UUID runId = runs.forReport(report).getFirst().runId();
    // Failing it again changes nothing, and counts nothing.
    assertThat(runs.fail(runId, "again")).isFalse();

    assertThat(value(scrape(), "recon_runs_failed_total{application=\"payment-service\"}"))
        .isEqualTo(before + 1);
  }

  /**
   * An operation set aside for review has its own alert; it does not also hold the oldest-pending
   * age up for ever.
   */
  @Test
  void theOldestPendingAgeLeavesOutOperationsSetAsideForReview() {
    String payment = create("order-" + UUID.randomUUID(), 1_000);
    jdbc.sql(
            """
            UPDATE psp_operations
               SET needs_review = true, created_at = now() - interval '3650 days'
             WHERE payment_id = ?::uuid""")
        .param(payment)
        .update();

    assertThat(
            value(
                scrape(), "psp_operation_oldest_pending_seconds{application=\"payment-service\"}"))
        .isLessThan(3650 * 86_400.0);
  }
}
