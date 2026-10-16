package com.ledgerpay.payment.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.settlement.SettlementImporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reconciliation runs over HTTP and PostgreSQL (design §11.2, §11.3): the single-fault report
 * variants (AT-17), a lost webhook found and fixed (AT-15, as the books see it), and a posting
 * committed while a run reads the books (AT-18). Each test has a business day of its own, long ago,
 * so no other test's operations are in its snapshot.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledgerpay.ops.api-key-hash=" + ReconRunIntegrationTest.OPS_KEY_HASH,
      "ledgerpay.psp.worker.enabled=false",
      "ledgerpay.inbox.processor.enabled=false",
      "ledgerpay.recon.sweeper.enabled=false"
    })
@Import({PostgresTestcontainersConfiguration.class, ReconRunIntegrationTest.Probe.class})
class ReconRunIntegrationTest {

  static final String OPS_KEY = "ops_test_key";
  static final String OPS_KEY_HASH =
      "17da52acda373b82d53e6601abffb233bc9a735579df11d2c78596cfa5890134";
  static final String HEADER =
      "psp_reference,psp_request_id,txn_type,merchant_id,amount_minor,currency,succeeded_at\r\n";

  /** Pauses or fails a run between the snapshot's two reads, when a test asks it to. */
  static class PausingProbe implements SnapshotProbe {
    volatile CountDownLatch reached;
    volatile CountDownLatch release;
    volatile RuntimeException failure;

    @Override
    public void betweenReads(UUID runId) {
      if (failure != null) {
        throw failure;
      }
      if (release != null) {
        reached.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    void reset() {
      reached = null;
      release = null;
      failure = null;
    }
  }

  @TestConfiguration
  static class Probe {
    @Bean
    PausingProbe pausingProbe() {
      return new PausingProbe();
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired SettlementImporter importer;
  @Autowired ReconRuns runs;
  @Autowired PausingProbe probe;

  final HttpClient http = HttpClient.newHttpClient();
  String merchant;
  String psp;
  LocalDate day;

  /** Our side of a test day: capture P1 with refund R1, captures P2 and P3. */
  record Operation(String reference, String requestId, String type, long amount) {
    String row(LocalDate day, String merchant) {
      return String.join(
              ",",
              reference,
              requestId,
              type,
              merchant,
              Long.toString(amount),
              "GBP",
              day + "T10:00:00Z")
          + "\r\n";
    }
  }

  Map<String, Operation> ops;

  @BeforeEach
  void newDay() {
    probe.reset();
    merchant = "m_" + UUID.randomUUID();
    psp = "test-" + UUID.randomUUID().toString().substring(0, 13);
    day = LocalDate.of(2001, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(30_000));
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, "hash_" + merchant)
        .update();
    ops = new LinkedHashMap<>();
  }

  @AfterEach
  void release() {
    if (probe.release != null) {
      probe.release.countDown();
    }
    probe.reset();
  }

  /** Inserts a payment and one capture or refund operation for it; returns the operation. */
  Operation operation(String name, String type, long amount, String status) {
    UUID payment = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO payments (id, merchant_id, merchant_reference, amount_minor, currency,
                                  status, captured_minor)
            VALUES (?, ?, ?, ?, 'GBP', 'CAPTURED', ?)""")
        .params(payment, merchant, name + "-" + UUID.randomUUID(), amount, amount)
        .update();
    var operation =
        new Operation("psp_" + UUID.randomUUID(), "req_" + UUID.randomUUID(), type, amount);
    jdbc.sql(
            """
            INSERT INTO psp_operations (id, payment_id, type, psp_request_id, amount_minor,
                                        currency, status, psp_reference, succeeded_at)
            VALUES (?, ?, ?, ?, ?, 'GBP', ?, ?, ?)""")
        .params(
            UUID.randomUUID(),
            payment,
            type,
            operation.requestId(),
            amount,
            status,
            operation.reference(),
            status.equals("SUCCEEDED") ? day.atTime(10, 0).atOffset(ZoneOffset.UTC) : null)
        .update();
    ops.put(name, operation);
    return operation;
  }

  void standardDay(String p2Status) {
    operation("P1", "CAPTURE", 10000, "SUCCEEDED");
    operation("R1", "REFUND", 3000, "SUCCEEDED");
    operation("P2", "CAPTURE", 20000, p2Status);
    operation("P3", "CAPTURE", 5000, "SUCCEEDED");
  }

  /** The report the PSP would send for the standard day. */
  List<String> standardRows() {
    List<String> rows = new ArrayList<>();
    ops.values().forEach(op -> rows.add(op.row(day, merchant)));
    return rows;
  }

  UUID report(List<String> rows) {
    var imported =
        importer.importReport(
            psp, day, (HEADER + String.join("", rows)).getBytes(StandardCharsets.UTF_8));
    assertThat(imported.report().valid()).as(imported.report().errors().toString()).isTrue();
    return imported.report().reportId();
  }

  record Response(int status, JsonNode body) {}

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
    return new Response(
        response.statusCode(), json.readTree(response.body().isEmpty() ? "{}" : response.body()));
  }

  UUID startRun(UUID reportId) throws Exception {
    Response started = send("POST", "/ops/recon-runs", "{\"reportId\": \"" + reportId + "\"}");
    assertThat(started.status()).as(started.body().toString()).isEqualTo(202);
    return UUID.fromString(started.body().path("runId").asText());
  }

  JsonNode awaitFinished(UUID runId) {
    return await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> send("GET", "/ops/recon-runs/" + runId, null).body(),
            run -> !run.path("status").asText().equals("RUNNING"));
  }

  JsonNode run(UUID reportId) throws Exception {
    JsonNode run = awaitFinished(startRun(reportId));
    assertThat(run.path("status").asText()).as(run.toString()).isEqualTo("COMPLETED");
    return run;
  }

  /** Category of each item, keyed by PSP reference, translated back to the test's names. */
  Map<String, String> categories(JsonNode run) throws Exception {
    JsonNode page =
        send("GET", "/ops/recon-runs/" + run.path("runId").asText() + "/items", null).body();
    Map<String, String> byName = new LinkedHashMap<>();
    for (JsonNode item : page.path("items")) {
      String reference = item.path("pspReference").asText();
      String name =
          ops.entrySet().stream()
              .filter(e -> e.getValue().reference().equals(reference))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse(reference);
      byName.put(name, item.path("category").asText());
    }
    return byName;
  }

  JsonNode category(JsonNode run, String category) {
    return run.path("summary").path("categories").path(category);
  }

  @Test
  void aReportThatAgreesIsAllMatchedWithTheDaysTotals() throws Exception {
    standardDay("SUCCEEDED");

    JsonNode run = run(report(standardRows()));

    assertThat(categories(run))
        .containsExactly(
            Map.entry("P1", "MATCHED"),
            Map.entry("R1", "MATCHED"),
            Map.entry("P2", "MATCHED"),
            Map.entry("P3", "MATCHED"));
    JsonNode summary = run.path("summary");
    assertThat(summary.path("externalRows").asInt()).isEqualTo(4);
    assertThat(summary.path("uniqueTransactions").asInt()).isEqualTo(4);
    assertThat(summary.path("external").path("capturedMinor").asLong()).isEqualTo(35000);
    assertThat(summary.path("external").path("refundedMinor").asLong()).isEqualTo(3000);
    assertThat(summary.path("external").path("netMinor").asLong()).isEqualTo(32000);
    assertThat(summary.path("internal")).isEqualTo(summary.path("external"));
    assertThat(category(run, "MATCHED").path("items").asInt()).isEqualTo(4);
    assertThat(run.path("rulesVersion").asText()).isEqualTo(Reconciler.RULES_VERSION);
    assertThat(Instant.parse(run.path("snapshotAt").asText()))
        .isBefore(Instant.parse(run.path("finishedAt").asText()));
  }

  /** AT-17: each single-fault variant of the report gets the right category, and only that one. */
  @Test
  void eachSingleFaultVariantIsClassifiedCorrectly() throws Exception {
    standardDay("SUCCEEDED");
    List<String> rows = standardRows();
    Operation p1 = ops.get("P1");
    Operation p3 = ops.get("P3");

    List<String> duplicated = new ArrayList<>(rows);
    duplicated.add(1, p1.row(day, merchant));
    assertThat(categories(run(report(duplicated))))
        .containsEntry("P1", "DUPLICATE_EXTERNAL")
        .containsEntry("R1", "MATCHED")
        .containsEntry("P2", "MATCHED")
        .containsEntry("P3", "MATCHED")
        .hasSize(4);

    List<String> missingP3 = new ArrayList<>(rows);
    missingP3.remove(p3.row(day, merchant));
    assertThat(categories(run(report(missingP3))))
        .containsEntry("P3", "MISSING_EXTERNAL")
        .containsEntry("P1", "MATCHED")
        .hasSize(4);

    List<String> amount = new ArrayList<>(rows);
    amount.set(3, p3.row(day, merchant).replace(",5000,", ",5100,"));
    JsonNode amountRun = run(report(amount));
    assertThat(categories(amountRun)).containsEntry("P3", "AMOUNT_MISMATCH").hasSize(4);
    JsonNode mismatch =
        send(
                "GET",
                "/ops/recon-runs/"
                    + amountRun.path("runId").asText()
                    + "/items?category=AMOUNT_MISMATCH",
                null)
            .body();
    assertThat(mismatch.path("total").asInt()).isEqualTo(1);
    JsonNode item = mismatch.path("items").get(0);
    assertThat(item.path("diff").path("amount_minor").path("external").asLong()).isEqualTo(5100);
    assertThat(item.path("diff").path("amount_minor").path("internal").asLong()).isEqualTo(5000);
    assertThat(item.path("externalLines").get(0).path("lineNo").asInt()).isEqualTo(5);
    assertThat(item.path("internal").path("amountMinor").asLong()).isEqualTo(5000);

    List<String> unknown = new ArrayList<>(rows);
    unknown.add(
        new Operation("psp_unknown_" + UUID.randomUUID(), "req_unknown", "CAPTURE", 700)
            .row(day, merchant));
    JsonNode unknownRun = run(report(unknown));
    assertThat(categories(unknownRun)).containsValue("MISSING_INTERNAL").hasSize(5);
    assertThat(category(unknownRun, "MISSING_INTERNAL").path("amountMinor").asLong())
        .isEqualTo(700);
  }

  /** AT-15 as the books see it: a capture whose webhook was lost, until it is booked. */
  @Test
  void aLostWebhookIsAStatusMismatchUntilBookedAndTheOldRunIsKept() throws Exception {
    standardDay("PENDING");
    UUID reportId = report(standardRows());

    JsonNode first = run(reportId);
    assertThat(categories(first)).containsEntry("P2", "STATUS_MISMATCH");
    assertThat(category(first, "STATUS_MISMATCH").path("amountMinor").asLong()).isEqualTo(20000);
    assertThat(first.path("summary").path("internal").path("capturedMinor").asLong())
        .isEqualTo(15000);

    succeed(ops.get("P2"));
    JsonNode second = run(reportId);

    assertThat(categories(second)).containsEntry("P2", "MATCHED");
    JsonNode again = send("GET", "/ops/recon-runs/" + first.path("runId").asText(), null).body();
    assertThat(again).isEqualTo(first);
    assertThat(categories(again)).containsEntry("P2", "STATUS_MISMATCH");
    assertThat(
            send("GET", "/ops/recon-runs?reportId=" + reportId, null)
                .body()
                .findValuesAsText("runId"))
        .containsExactly(first.path("runId").asText(), second.path("runId").asText());
  }

  /** What the money transaction does to the operation when the outcome arrives. */
  void succeed(Operation operation) {
    jdbc.sql(
            """
            UPDATE psp_operations SET status = 'SUCCEEDED', succeeded_at = ?
             WHERE psp_reference = ?""")
        .params(day.atTime(10, 0).atOffset(ZoneOffset.UTC), operation.reference())
        .update();
  }

  /** AT-18: a posting committed while the run reads the books is not in that run, only the next. */
  @Test
  void aPostingCommittedDuringTheSnapshotIsNotInThatRun() throws Exception {
    standardDay("PENDING");
    UUID reportId = report(standardRows());
    probe.reached = new CountDownLatch(1);
    probe.release = new CountDownLatch(1);

    UUID runId = startRun(reportId);
    assertThat(probe.reached.await(10, TimeUnit.SECONDS)).isTrue();
    succeed(ops.get("P2"));
    probe.release.countDown();
    JsonNode during = awaitFinished(runId);
    probe.reset();

    assertThat(during.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(categories(during)).containsEntry("P2", "STATUS_MISMATCH");
    assertThat(categories(run(reportId))).containsEntry("P2", "MATCHED");
  }

  @Test
  void anInvalidOrUnknownReportCannotBeRun() throws Exception {
    var invalid =
        importer.importReport(
            psp,
            day,
            (HEADER + "psp_1,req_1,CAPTURE,m,1.5,GBP," + day + "T10:00:00Z\n")
                .getBytes(StandardCharsets.UTF_8));

    Response refused =
        send("POST", "/ops/recon-runs", "{\"reportId\": \"" + invalid.report().reportId() + "\"}");
    assertThat(refused.status()).isEqualTo(409);
    assertThat(refused.body().path("code").asText()).isEqualTo("INVALID_STATE");
    assertThat(runs.forReport(invalid.report().reportId())).isEmpty();

    assertThat(
            send("POST", "/ops/recon-runs", "{\"reportId\": \"" + UUID.randomUUID() + "\"}")
                .status())
        .isEqualTo(404);
    assertThat(send("POST", "/ops/recon-runs", "{}").status()).isEqualTo(400);
    assertThat(send("GET", "/ops/recon-runs/" + UUID.randomUUID(), null).status()).isEqualTo(404);
    assertThat(send("GET", "/ops/recon-runs/" + UUID.randomUUID() + "/items", null).status())
        .isEqualTo(404);
  }

  @Test
  void itemsArePagedAndFilteredByCategory() throws Exception {
    standardDay("PENDING");
    String runId = run(report(standardRows())).path("runId").asText();

    JsonNode page =
        send("GET", "/ops/recon-runs/" + runId + "/items?offset=1&limit=2", null).body();
    assertThat(page.path("total").asInt()).isEqualTo(4);
    assertThat(page.path("items").findValuesAsText("itemNo")).containsExactly("2", "3");

    JsonNode status =
        send("GET", "/ops/recon-runs/" + runId + "/items?category=STATUS_MISMATCH", null).body();
    assertThat(status.path("total").asInt()).isEqualTo(1);
    assertThat(status.path("items").get(0).path("internal").path("status").asText())
        .isEqualTo("PENDING");
    assertThat(status.path("items").get(0).path("diff").path("status").path("external").asText())
        .isEqualTo("SUCCEEDED");

    assertThat(send("GET", "/ops/recon-runs/" + runId + "/items?category=NOPE", null).status())
        .isEqualTo(400);
    assertThat(send("GET", "/ops/recon-runs/" + runId + "/items?limit=1001", null).status())
        .isEqualTo(400);
  }

  @Test
  void aCompletedRunAndItsItemsCannotBeChanged() throws Exception {
    standardDay("SUCCEEDED");
    UUID runId = UUID.fromString(run(report(standardRows())).path("runId").asText());

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE recon_runs SET summary = '{}' WHERE id = ?").param(runId).update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("cannot change");
    assertThatThrownBy(() -> jdbc.sql("DELETE FROM recon_runs WHERE id = ?").param(runId).update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE recon_items SET category = 'MATCHED' WHERE run_id = ?")
                    .param(runId)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO recon_items (run_id, item_no, category, external_line_nos)
                        VALUES (?, 99, 'MATCHED', '{2}')""")
                    .param(runId)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("not running");
  }

  @Test
  void aRunThatFailsSaysWhyAndKeepsNoItems() throws Exception {
    standardDay("SUCCEEDED");
    probe.failure = new IllegalStateException("books unreadable");

    JsonNode run = awaitFinished(startRun(report(standardRows())));

    assertThat(run.path("status").asText()).isEqualTo("FAILED");
    assertThat(run.path("error").asText()).isEqualTo("IllegalStateException: books unreadable");
    assertThat(run.path("summary").isNull()).isTrue();
    assertThat(runs.countItems(UUID.fromString(run.path("runId").asText()), null)).isZero();
  }

  @Test
  void aRunGivenUpAsAbandonedStaysFailedWhenItFinishesLate() throws Exception {
    standardDay("SUCCEEDED");
    probe.reached = new CountDownLatch(1);
    probe.release = new CountDownLatch(1);
    UUID runId = startRun(report(standardRows()));
    assertThat(probe.reached.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(runs.failAbandoned(Duration.ZERO)).isPositive();
    probe.release.countDown();

    await()
        .during(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(5))
        .until(() -> runs.find(runId).orElseThrow().status().equals("FAILED"));
    ReconRun run = runs.find(runId).orElseThrow();
    assertThat(run.error()).startsWith("abandoned");
    assertThat(run.summary()).isNull();
    assertThat(runs.countItems(runId, null)).isZero();
  }
}
