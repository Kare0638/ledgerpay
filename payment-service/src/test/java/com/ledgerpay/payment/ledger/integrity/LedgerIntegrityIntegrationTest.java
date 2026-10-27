package com.ledgerpay.payment.ledger.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.ledger.EntryType;
import com.ledgerpay.payment.ledger.Ledger;
import com.ledgerpay.payment.ledger.PostingRules;
import com.ledgerpay.payment.settlement.SettlementImporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The ledger integrity check (design §11.2, #19) finds books corrupted behind the database's back,
 * with the triggers switched off as a bad migration or manual fix would (AT-19), even on a day
 * whose PSP report matches to the penny.
 *
 * <p>It corrupts the books on purpose, so it has a database of its own, emptied before each test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledgerpay.ops.api-key-hash=" + LedgerIntegrityIntegrationTest.OPS_KEY_HASH,
      "ledgerpay.psp.worker.enabled=false",
      "ledgerpay.inbox.processor.enabled=false",
      "ledgerpay.outbox.relay.enabled=false",
      "ledgerpay.recon.sweeper.enabled=false"
    })
// Spring Boot leaves metrics export off in tests; this test reads the gauge from
// /actuator/prometheus.
@AutoConfigureObservability(tracing = false)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class LedgerIntegrityIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(PostgresTestcontainersConfiguration.IMAGE);

  static final String OPS_KEY = "ops_test_key";
  static final String OPS_KEY_HASH =
      "17da52acda373b82d53e6601abffb233bc9a735579df11d2c78596cfa5890134";
  static final String PSP = "mock-psp";
  static final LocalDate DAY = LocalDate.parse("2026-10-13");

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired Ledger ledger;
  @Autowired TransactionTemplate transactions;
  @Autowired SettlementImporter importer;

  final HttpClient http = HttpClient.newHttpClient();

  /** A booked operation: its payment, operation and journal. */
  record Booked(
      UUID paymentId,
      UUID operationId,
      UUID journalId,
      String merchant,
      String reference,
      String requestId,
      String type,
      long amount) {
    String row() {
      return String.join(
              ",",
              reference,
              requestId,
              type,
              merchant,
              Long.toString(amount),
              "GBP",
              DAY + "T10:00:00Z")
          + "\r\n";
    }
  }

  @BeforeEach
  void emptyBooks() {
    corrupt(
        "TRUNCATE merchants, settlement_reports, ledger_integrity_checks, accounts,"
            + " outbox_events RESTART IDENTITY CASCADE");
  }

  /** Runs statements with the triggers off, as a migration or a manual fix gone wrong would. */
  void corrupt(String... statements) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL session_replication_role = replica").update();
          for (String statement : statements) {
            jdbc.sql(statement).update();
          }
        });
  }

  String merchant() {
    String id = "m_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(id, "h_" + id)
        .update();
    return id;
  }

  /** A £100 capture at 1 %, booked the way the money transaction books it. */
  Booked capture(String merchant, long amount) {
    UUID payment = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO payments (id, merchant_id, merchant_reference, amount_minor, currency,
                                  status, captured_minor)
            VALUES (?, ?, ?, ?, 'GBP', 'CAPTURED', ?)""")
        .params(payment, merchant, "order-" + UUID.randomUUID(), amount, amount)
        .update();
    return book(payment, merchant, "CAPTURE", amount);
  }

  Booked refund(Booked capture, long amount) {
    jdbc.sql("UPDATE payments SET refunded_minor = refunded_minor + ? WHERE id = ?")
        .params(amount, capture.paymentId())
        .update();
    return book(capture.paymentId(), capture.merchant(), "REFUND", amount);
  }

  Booked book(UUID payment, String merchant, String type, long amount) {
    UUID operation = UUID.randomUUID();
    String reference = "psp_" + UUID.randomUUID();
    String requestId = "req_" + UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO psp_operations (id, payment_id, type, psp_request_id, amount_minor,
                                        currency, status, psp_reference, succeeded_at)
            VALUES (?, ?, ?, ?, ?, 'GBP', 'SUCCEEDED', ?, ?)""")
        .params(
            operation,
            payment,
            type,
            requestId,
            amount,
            reference,
            DAY.atTime(10, 0).atOffset(ZoneOffset.UTC))
        .update();
    Money money = Money.of(amount, Money.GBP);
    UUID journal =
        transactions.execute(
            status ->
                type.equals("CAPTURE")
                    ? ledger.post(
                        operation,
                        EntryType.CAPTURE,
                        PostingRules.capture(PSP, merchant, money, 100))
                    : ledger.post(
                        operation, EntryType.REFUND, PostingRules.refund(PSP, merchant, money)));
    return new Booked(payment, operation, journal, merchant, reference, requestId, type, amount);
  }

  record Response(int status, JsonNode body, String text) {}

  Response send(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + OPS_KEY)
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    String text = response.body();
    JsonNode parsed = text.startsWith("{") || text.startsWith("[") ? json.readTree(text) : null;
    return new Response(response.statusCode(), parsed, text);
  }

  JsonNode check() throws Exception {
    Response response = send("POST", "/ops/ledger-integrity-checks", null);
    assertThat(response.status()).as(response.text()).isEqualTo(201);
    return response.body();
  }

  List<String> checks(JsonNode check) {
    List<String> found = new ArrayList<>();
    check.path("violations").forEach(v -> found.add(v.path("check").asText()));
    return found;
  }

  String gauge() throws Exception {
    return send("GET", "/actuator/prometheus", null)
        .text()
        .lines()
        .filter(line -> line.startsWith("ledger_invariant_violations"))
        .filter(line -> !line.startsWith("ledger_invariant_violations_"))
        // Every series carries the application label; the value is what is compared.
        .map(line -> line.replaceFirst("\\{[^}]*\\}", ""))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void booksThatAgreeWithThemselvesPass() throws Exception {
    String merchant = merchant();
    Booked capture = capture(merchant, 10000);
    refund(capture, 3000);
    capture(merchant(), 5000);

    JsonNode check = check();

    assertThat(check.path("status").asText()).isEqualTo("PASSED");
    assertThat(check.path("trigger").asText()).isEqualTo("ON_DEMAND");
    assertThat(check.path("journals").asLong()).isEqualTo(3);
    assertThat(check.path("postings").asLong()).isEqualTo(8);
    assertThat(check.path("violationCount").asInt()).isZero();
    assertThat(check.path("violations")).isEmpty();
    String id = check.path("checkId").asText();
    assertThat(send("GET", "/ops/ledger-integrity-checks/" + id, null).body()).isEqualTo(check);
    assertThat(send("GET", "/ops/ledger-integrity-checks?limit=5", null).body().get(0))
        .isEqualTo(check);
    assertThat(gauge()).isEqualTo("ledger_invariant_violations 0.0");
  }

  /**
   * AT-19: with a journal deleted, a reconciliation of a report that matches the books item by item
   * is all MATCHED, yet the check that runs with it fails and alerts.
   */
  @Test
  void aDeletedJournalFailsTheCheckEvenWhenThePspReportMatches(CapturedOutput output)
      throws Exception {
    String merchant = merchant();
    Booked capture = capture(merchant, 10000);
    Booked refund = refund(capture, 3000);
    Booked other = capture(merchant, 5000);
    corrupt(
        "DELETE FROM postings WHERE entry_id = '" + other.journalId() + "'",
        "DELETE FROM journal_entries WHERE id = '" + other.journalId() + "'");

    UUID reportId =
        importer
            .importReport(
                PSP,
                DAY,
                ("psp_reference,psp_request_id,txn_type,merchant_id,amount_minor,currency,succeeded_at\r\n"
                        + capture.row()
                        + refund.row()
                        + other.row())
                    .getBytes(StandardCharsets.UTF_8))
            .report()
            .reportId();
    String runId =
        send("POST", "/ops/recon-runs", "{\"reportId\": \"" + reportId + "\"}")
            .body()
            .path("runId")
            .asText();
    JsonNode run =
        await()
            .atMost(Duration.ofSeconds(20))
            .until(
                () -> send("GET", "/ops/recon-runs/" + runId, null).body(),
                r -> r.path("status").asText().equals("COMPLETED"));

    assertThat(run.path("summary").path("categories").path("MATCHED").path("items").asInt())
        .isEqualTo(3);
    assertThat(run.path("summary").path("external"))
        .isEqualTo(run.path("summary").path("internal"));
    JsonNode integrity = run.path("ledgerIntegrity");
    assertThat(integrity.path("status").asText()).isEqualTo("FAILED");
    assertThat(integrity.path("violationCount").asInt()).isEqualTo(2);

    JsonNode check =
        send("GET", "/ops/ledger-integrity-checks/" + integrity.path("checkId").asText(), null)
            .body();
    assertThat(check.path("trigger").asText()).isEqualTo("RECON_RUN");
    assertThat(check.path("reconRunId").asText()).isEqualTo(runId);
    assertThat(checks(check)).containsExactly("MISSING_JOURNAL", "PAYMENT_TOTALS_MISMATCH");
    assertThat(check.path("violations").get(0).path("subject").asText())
        .isEqualTo("psp_operation " + other.operationId());
    assertThat(check.path("violations").get(1).path("detail").asText())
        .isEqualTo("captured 5000 and refunded 0, but the journals book 0 and 0");
    assertThat(gauge()).isEqualTo("ledger_invariant_violations 2.0");
    assertThat(output).contains("LEDGER INTEGRITY CHECK", "FAILED with 2 violation(s)");
    // One check per run: a second would break the run's page, which joins it.
    assertThatThrownBy(() -> insertCheck(UUID.fromString(runId), 0))
        .isInstanceOf(DuplicateKeyException.class);
  }

  /** AT-19: a corrupted cache of the ledger, a payment's captured amount. */
  @Test
  void aCorruptedPaymentTotalFailsTheCheck() throws Exception {
    Booked capture = capture(merchant(), 10000);
    jdbc.sql("UPDATE payments SET captured_minor = 9000 WHERE id = ?")
        .param(capture.paymentId())
        .update();

    JsonNode check = check();

    assertThat(check.path("status").asText()).isEqualTo("FAILED");
    assertThat(checks(check)).containsExactly("PAYMENT_TOTALS_MISMATCH");
    assertThat(check.path("violations").get(0).path("subject").asText())
        .isEqualTo("payment " + capture.paymentId());
  }

  @Test
  void aPostingChangedOnItsOwnUnbalancesItsJournalAndTheLedger() throws Exception {
    Booked capture = capture(merchant(), 10000);
    corrupt(
        "UPDATE postings SET amount_minor = 10500 WHERE entry_id = '"
            + capture.journalId()
            + "' AND starts_with(account_id, 'psp_receivable:')");

    JsonNode check = check();

    assertThat(checks(check))
        .containsExactly(
            "UNBALANCED_JOURNAL",
            "LEDGER_NOT_ZERO",
            "JOURNAL_OPERATION_MISMATCH",
            "PAYMENT_TOTALS_MISMATCH");
    assertThat(check.path("violations").get(0).path("detail").asText())
        .isEqualTo("GBP postings sum to 500, not 0");
  }

  /** A balanced journal can still be wrong: it no longer books its operation's amount. */
  @Test
  void aJournalRebalancedByHandIsStillCaught() throws Exception {
    Booked capture = capture(merchant(), 10000);
    corrupt(
        "UPDATE postings SET amount_minor = amount_minor + 100 WHERE entry_id = '"
            + capture.journalId()
            + "' AND starts_with(account_id, 'psp_receivable:')",
        "UPDATE postings SET amount_minor = amount_minor - 100 WHERE entry_id = '"
            + capture.journalId()
            + "' AND starts_with(account_id, 'merchant_payable:')");

    JsonNode check = check();

    assertThat(checks(check))
        .containsExactly("JOURNAL_OPERATION_MISMATCH", "PAYMENT_TOTALS_MISMATCH");
    assertThat(check.path("violations").get(0).path("detail").asText())
        .isEqualTo(
            "PSP receivable moved by 10100, expected 10000; merchant "
                + capture.merchant()
                + " payable moved by -10000, expected -9900");
  }

  @Test
  void aMerchantShareMovedToAnotherMerchantIsCaught() throws Exception {
    Booked capture = capture(merchant(), 10000);
    String other = merchant();
    capture(other, 100);
    corrupt(
        "UPDATE postings SET account_id = 'merchant_payable:"
            + other
            + "' WHERE entry_id = '"
            + capture.journalId()
            + "' AND starts_with(account_id, 'merchant_payable:')");

    JsonNode check = check();

    assertThat(checks(check)).containsExactly("JOURNAL_OPERATION_MISMATCH");
    assertThat(check.path("violations").get(0).path("detail").asText())
        .isEqualTo(
            "merchant "
                + capture.merchant()
                + " payable moved by 0, expected -9900; posted to accounts outside the"
                + " operation's: merchant_payable:"
                + other);
  }

  /**
   * Balanced, the receivable right and no other merchant touched, yet the merchant is owed nothing:
   * the whole capture went to fee revenue.
   */
  @Test
  void aBalancedJournalThatRoutesTheMerchantsShareToFeesIsCaught() throws Exception {
    Booked capture = capture(merchant(), 10000);
    corrupt(
        "DELETE FROM postings WHERE entry_id = '"
            + capture.journalId()
            + "' AND starts_with(account_id, 'merchant_payable:')",
        "UPDATE postings SET amount_minor = -10000 WHERE entry_id = '"
            + capture.journalId()
            + "' AND account_id = 'fee_revenue'");

    JsonNode check = check();

    assertThat(checks(check)).containsExactly("JOURNAL_OPERATION_MISMATCH");
    assertThat(check.path("violations").get(0).path("detail").asText())
        .isEqualTo(
            "merchant "
                + capture.merchant()
                + " payable moved by 0, expected -9900; fee revenue moved by -10000, expected"
                + " -100");
  }

  /** Our receivable is the configured PSP's; another PSP's receivable is the wrong account. */
  @Test
  void aCaptureBookedToAnotherPspsReceivableIsCaught() throws Exception {
    Booked capture = capture(merchant(), 10000);
    jdbc.sql(
            "INSERT INTO accounts (id, type, currency) VALUES ('psp_receivable:other-psp',"
                + " 'ASSET', 'GBP') ON CONFLICT DO NOTHING")
        .update();
    corrupt(
        "UPDATE postings SET account_id = 'psp_receivable:other-psp' WHERE entry_id = '"
            + capture.journalId()
            + "' AND account_id = 'psp_receivable:"
            + PSP
            + "'");

    JsonNode check = check();

    assertThat(checks(check))
        .containsExactly("JOURNAL_OPERATION_MISMATCH", "PAYMENT_TOTALS_MISMATCH");
    assertThat(check.path("violations").get(0).path("detail").asText())
        .isEqualTo(
            "PSP receivable moved by 0, expected 10000; posted to accounts outside the"
                + " operation's: psp_receivable:other-psp");
  }

  /** A check stored as another instance would, after this one's own. */
  void insertCheck(UUID reconRunId, int violations) {
    jdbc.sql(
            """
            INSERT INTO ledger_integrity_checks
                   (id, trigger, recon_run_id, status, snapshot_at, journals, postings,
                    violation_count, violations, checked_at)
            VALUES (?, ?, ?, ?, now(), 0, 0, ?, ?::jsonb, now() + interval '1 second')""")
        .params(
            UUID.randomUUID(),
            reconRunId == null ? "ON_DEMAND" : "RECON_RUN",
            reconRunId,
            violations == 0 ? "PASSED" : "FAILED",
            violations,
            violations == 0 ? null : "[]")
        .update();
  }

  /**
   * The gauge is the latest stored check, whichever instance ran it: a failure recorded elsewhere
   * raises it here, and a later pass elsewhere clears it.
   */
  @Test
  void theGaugeFollowsTheLatestStoredCheckFromAnyInstance() throws Exception {
    capture(merchant(), 100);
    check();
    assertThat(gauge()).isEqualTo("ledger_invariant_violations 0.0");

    insertCheck(null, 7);
    assertThat(gauge()).isEqualTo("ledger_invariant_violations 7.0");

    Thread.sleep(1_100);
    insertCheck(null, 0);
    assertThat(gauge()).isEqualTo("ledger_invariant_violations 0.0");
  }

  /** Metrics show internal state, the ledger alarm included: they need the ops key. */
  @Test
  void metricsNeedTheOpsKeyButHealthDoesNot() throws Exception {
    var noKey =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var wrongKey =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                .header("Authorization", "Bearer mk_merchant_key")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var health =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(noKey.statusCode()).isEqualTo(401);
    assertThat(wrongKey.statusCode()).isEqualTo(401);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(send("GET", "/actuator/prometheus", null).status()).isEqualTo(200);
  }

  @Test
  void aJournalForAnOperationThatDidNotSucceedIsUnexpected() throws Exception {
    Booked capture = capture(merchant(), 10000);
    jdbc.sql("UPDATE psp_operations SET status = 'FAILED', succeeded_at = NULL WHERE id = ?")
        .param(capture.operationId())
        .update();

    JsonNode check = check();

    assertThat(checks(check)).containsExactly("UNEXPECTED_JOURNAL");
    assertThat(check.path("violations").get(0).path("detail").asText())
        .isEqualTo("CAPTURE journal for FAILED CAPTURE " + capture.operationId());
  }

  @Test
  void aJournalLeftWithOnePostingIsUnbalanced() throws Exception {
    Booked capture = capture(merchant(), 10000);
    corrupt(
        "DELETE FROM postings WHERE entry_id = '"
            + capture.journalId()
            + "' AND NOT starts_with(account_id, 'psp_receivable:')");

    JsonNode check = check();

    assertThat(check.path("violations").get(0).path("detail").asText())
        .isEqualTo("has 1 posting(s); at least 2 are required");
    assertThat(checks(check))
        .startsWith("UNBALANCED_JOURNAL", "UNBALANCED_JOURNAL", "LEDGER_NOT_ZERO");
  }

  @Test
  void checksAreKeptAndListedNewestFirst() throws Exception {
    capture(merchant(), 100);
    String first = check().path("checkId").asText();
    String second = check().path("checkId").asText();

    JsonNode listed = send("GET", "/ops/ledger-integrity-checks?limit=2", null).body();

    assertThat(listed.findValuesAsText("checkId")).containsExactly(second, first);
    assertThat(send("GET", "/ops/ledger-integrity-checks?limit=0", null).status()).isEqualTo(400);
    assertThat(send("GET", "/ops/ledger-integrity-checks/" + UUID.randomUUID(), null).status())
        .isEqualTo(404);
  }
}
