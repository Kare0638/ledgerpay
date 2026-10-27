package com.ledgerpay.payment.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.TestDays;
import com.ledgerpay.payment.ledger.EntryType;
import com.ledgerpay.payment.ledger.Ledger;
import com.ledgerpay.payment.ledger.PostingRules;
import com.ledgerpay.payment.reconciliation.ReconCategory;
import com.ledgerpay.payment.reconciliation.ReconEngine;
import com.ledgerpay.payment.reconciliation.ReconItem;
import com.ledgerpay.payment.reconciliation.ReconRun;
import com.ledgerpay.payment.reconciliation.ReconRuns;
import com.ledgerpay.payment.settlement.SettlementImporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in experiment, never a CI model dependency. See docs/evaluation/README.md. */
@EnabledIfEnvironmentVariable(named = "LEDGERPAY_LIVE_EVAL", matches = "true")
@SpringBootTest(properties = "ledgerpay.recon.sweeper.enabled=false")
@Import(PostgresTestcontainersConfiguration.class)
class AssistantLiveEvaluationTest {
  static final String HEADER =
      "psp_reference,psp_request_id,txn_type,merchant_id,amount_minor,currency,succeeded_at\r\n";

  enum Scenario {
    LOST_WEBHOOK(ReconCategory.STATUS_MISMATCH, LikelyCause.OUTCOME_NOT_RECEIVED),
    DUPLICATE_ROW(ReconCategory.DUPLICATE_EXTERNAL, LikelyCause.DUPLICATE_IN_REPORT),
    MISSING_ROW(ReconCategory.MISSING_EXTERNAL, LikelyCause.MISSING_FROM_REPORT),
    WRONG_AMOUNT(ReconCategory.AMOUNT_MISMATCH, LikelyCause.FIELD_MISMATCH),
    UNKNOWN_TRANSACTION(ReconCategory.MISSING_INTERNAL, LikelyCause.UNKNOWN_TRANSACTION);

    final ReconCategory category;
    final LikelyCause cause;

    Scenario(ReconCategory category, LikelyCause cause) {
      this.category = category;
      this.cause = cause;
    }
  }

  record Fixture(Scenario scenario, ReconRun run, ReconItem item, ObjectNode facts) {}

  record Op(UUID id, String reference, String request, long amount) {}

  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired SettlementImporter importer;
  @Autowired ReconEngine engine;
  @Autowired ReconRuns runs;
  @Autowired AssistantTools tools;
  @Autowired AssistantStore store;
  @Autowired Assistant assistant;
  @Autowired AssistantProperties properties;
  @Autowired Ledger ledger;
  @Autowired TransactionTemplate transactions;

  final Path output =
      Path.of(System.getProperty("ledgerpay.eval.output", "target/assistant-evaluation.json"));
  ObjectNode report;

  @Test
  void compareTheSameFiveBreaksAgainstAFrozenPrecedentCorpus() throws Exception {
    assertThat(properties.resolveKey()).as("Run through scripts/with-gemini.sh").isNotBlank();
    GeminiClient gemini = new GeminiClient(properties, properties.resolveKey(), json);
    AssistantAgent agent = new AssistantAgent(gemini, tools, json, properties.maxTurns());
    report = json.createObjectNode();
    report.put("startedAt", Instant.now().toString());
    report.put("model", properties.model());
    report.put("embeddingModel", properties.embeddingModel());
    report.put("maxTurns", properties.maxTurns());
    report.put("maxPrecedents", 3);
    report.put("generationConfig", "API defaults; no temperature or seed override");
    report.put(
        "fixtureSource", "SQL-seeded demo-equivalent breaks, real import/reconciliation/ledger");
    report.putArray("training");
    report.putArray("evaluations");
    save();

    String corpusFile = System.getProperty("ledgerpay.eval.corpus", "");
    JsonNode corpus = corpusFile.isBlank() ? null : json.readTree(Path.of(corpusFile).toFile());
    if (corpus != null) {
      assertThat(corpus.path("training").size()).isEqualTo(5);
      report.put("corpusSourceModel", corpus.path("model").asText());
      report.put("corpusSourceStartedAt", corpus.path("startedAt").asText());
      report.put("corpusReused", true);
    }
    int corpusIndex = 0;
    // A separate day and fresh IDs per scenario: none of the held-out answers enter retrieval.
    for (Scenario scenario : Scenario.values()) {
      JsonNode source = corpus == null ? null : corpus.path("training").get(corpusIndex++);
      if (source != null) {
        assertThat(source.path("scenario").asText()).isEqualTo(scenario.name());
      }
      Fixture fixture = fixture(scenario, true, agent, source);
      ObjectNode entry = ((ArrayNode) report.path("training")).addObject();
      describe(entry, fixture);
      save();
      try {
        String signature = BreakSignature.of(fixture.facts());
        float[] embedding = gemini.embed(signature);
        long started = System.nanoTime();
        AgentResult result =
            source == null
                ? agent.analyse(fixture.run(), fixture.item(), fixture.facts(), List.of())
                : json.treeToValue(source.path("result"), AgentResult.class);
        entry.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        entry.set("result", json.valueToTree(result));
        // Review corpus labels before admission. This is a curated corpus, not a quality score.
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.analysis().likelyCause()).isEqualTo(scenario.cause);
        assertThat(
                AnalysisValidator.validate(
                        json.valueToTree(result.analysis()), fixture.facts().toString())
                    .valid())
            .as("corpus citations match the restored facts")
            .isTrue();
        UUID id =
            store.insert(
                fixture.run().runId(),
                fixture.item().itemNo(),
                source == null ? properties.model() : corpus.path("model").asText(),
                result,
                signature,
                embedding,
                List.of());
        StoredAnalysis stored = assistant.get(id);
        if (stored.proposal() != null) {
          assertThat(scenario).isEqualTo(Scenario.LOST_WEBHOOK);
          // The opt-in operator has authorised this isolated experiment; workers stay disabled.
          assistant.confirm(stored.proposal().proposalId(), "live-evaluation-corpus-review");
        }
        entry.put("analysisId", id.toString());
        if (source != null) {
          entry.put("sourceAnalysisId", source.path("analysisId").asText());
          entry.put("reused", true);
        }
      } catch (RuntimeException | AssertionError e) {
        entry.put("error", e.getMessage());
        throw e;
      } finally {
        save();
      }
    }

    int index = 0;
    for (Scenario scenario : Scenario.values()) {
      Fixture fixture = fixture(scenario, false, agent, null);
      String signature = BreakSignature.of(fixture.facts());
      // Fail if real embeddings/retrieval do not work: silently falling back is not a RAG arm.
      float[] embedding = gemini.embed(signature);
      List<Precedent> precedents =
          store.precedents(
              fixture.run().runId(),
              fixture.item().itemNo(),
              scenario.category.name(),
              embedding,
              3);
      assertThat(precedents).hasSize(3);
      assertThat(precedents.getFirst().analysis().likelyCause()).isEqualTo(scenario.cause);
      // Alternate order to reduce a simple first/second-arm bias.
      for (boolean rag : index++ % 2 == 0 ? List.of(false, true) : List.of(true, false)) {
        ObjectNode entry = ((ArrayNode) report.path("evaluations")).addObject();
        describe(entry, fixture);
        entry.put("rag", rag);
        entry.set("precedents", json.valueToTree(rag ? precedents : List.of()));
        long started = System.nanoTime();
        try {
          AgentResult result =
              agent.analyse(
                  fixture.run(), fixture.item(), fixture.facts(), rag ? precedents : List.of());
          entry.set("result", json.valueToTree(result));
          entry.put(
              "causeCorrect",
              result.analysis() != null && result.analysis().likelyCause() == scenario.cause);
          entry.put(
              "proposalCorrect",
              (result.proposal() != null) == (scenario == Scenario.LOST_WEBHOOK));
        } catch (GeminiClient.GeminiException e) {
          entry.put("error", e.getMessage());
        } finally {
          entry.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
          save();
        }
      }
    }
    report.put("finishedAt", Instant.now().toString());
    save();
    assertThat(report.path("evaluations").size()).isEqualTo(10);
    assertThat(jdbc.sql("SELECT count(*) FROM assistant_analyses").query(Long.class).single())
        .as("only training cases were persisted; the retrieval corpus stayed frozen")
        .isEqualTo(5);
  }

  private void describe(ObjectNode entry, Fixture fixture) {
    entry.put("scenario", fixture.scenario().name());
    entry.put("expectedCause", fixture.scenario().cause.name());
    entry.put("expectedProposal", fixture.scenario() == Scenario.LOST_WEBHOOK);
    entry.put("runId", fixture.run().runId().toString());
    entry.put("itemNo", fixture.item().itemNo());
    entry.set("facts", fixture.facts());
  }

  private void save() throws Exception {
    Files.createDirectories(output.toAbsolutePath().getParent());
    json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
  }

  private Fixture fixture(
      Scenario scenario, boolean training, AssistantAgent agent, JsonNode source) throws Exception {
    LocalDate next = TestDays.next();
    JsonNode oldBreak = source == null ? null : source.path("facts").path("get_break");
    JsonNode oldOp = oldBreak == null ? null : oldBreak.path("ourOperationAtSnapshot");
    LocalDate day =
        oldBreak == null ? next : LocalDate.parse(oldBreak.path("businessDate").asText());
    JsonNode oldRow = oldBreak == null ? null : oldBreak.path("reportLines").path(0);
    String merchant =
        oldBreak == null
            ? "m_eval_" + UUID.randomUUID()
            : (oldOp.isObject() ? oldOp : oldRow).path("merchantId").asText();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash, fee_bps) VALUES (?, ?, 100)")
        .params(merchant, "h_" + merchant)
        .update();
    long scale = training ? 2 : 1;
    Op control = operation(merchant, day, 10_000 * scale, false, null);
    boolean unknown = scenario == Scenario.UNKNOWN_TRANSACTION;
    long amount =
        (scenario == Scenario.LOST_WEBHOOK
                ? 20_000
                : scenario == Scenario.DUPLICATE_ROW ? 10_000 : 5_000)
            * scale;
    Op target =
        unknown
            ? new Op(
                null,
                oldRow == null ? "psp_" + UUID.randomUUID() : oldRow.path("pspReference").asText(),
                oldRow == null ? "req_" + UUID.randomUUID() : oldRow.path("pspRequestId").asText(),
                1_500 * scale)
            : operation(merchant, day, amount, scenario == Scenario.LOST_WEBHOOK, oldOp);
    String csv = HEADER + row(control, merchant, day, control.amount());
    if (scenario != Scenario.MISSING_ROW) {
      csv +=
          row(
              target,
              merchant,
              day,
              scenario == Scenario.WRONG_AMOUNT ? amount + 100 * scale : target.amount());
    }
    if (scenario == Scenario.DUPLICATE_ROW) {
      csv += row(target, merchant, day, target.amount());
    }
    var imported =
        importer.importReport("mock-psp", day, csv.getBytes(StandardCharsets.UTF_8)).report();
    UUID runId = engine.start(imported).runId();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> !runs.find(runId).orElseThrow().status().equals("RUNNING"));
    ReconRun run = runs.find(runId).orElseThrow();
    assertThat(run.status()).isEqualTo("COMPLETED");
    assertThat(run.ledgerIntegrity().status()).isEqualTo("PASSED");
    List<ReconItem> breaks = runs.items(runId, scenario.category, 0, 10);
    assertThat(breaks).hasSize(1);
    ReconItem item = breaks.getFirst();
    return new Fixture(scenario, run, item, agent.facts(run, item));
  }

  private Op operation(
      String merchant, LocalDate day, long amount, boolean pending, JsonNode source) {
    UUID payment =
        source == null ? UUID.randomUUID() : UUID.fromString(source.path("paymentId").asText());
    Op op =
        new Op(
            source == null ? UUID.randomUUID() : UUID.fromString(source.path("id").asText()),
            source == null ? "psp_" + UUID.randomUUID() : source.path("pspReference").asText(),
            source == null ? "req_" + UUID.randomUUID() : source.path("pspRequestId").asText(),
            amount);
    transactions.executeWithoutResult(
        tx -> {
          jdbc.sql(
                  """
          INSERT INTO payments (id, merchant_id, merchant_reference, amount_minor, currency,
                                status, captured_minor)
          VALUES (?, ?, ?, ?, 'GBP', ?, ?)""")
              .params(
                  payment,
                  merchant,
                  "order-" + UUID.randomUUID(),
                  amount,
                  pending ? "CAPTURE_PENDING" : "CAPTURED",
                  pending ? 0 : amount)
              .update();
          jdbc.sql(
                  """
          INSERT INTO psp_operations (id, payment_id, type, psp_request_id, amount_minor,
                                      currency, status, psp_reference, succeeded_at, next_attempt_at)
          VALUES (?, ?, 'CAPTURE', ?, ?, 'GBP', ?, ?, ?, now() + interval '15 minutes')""")
              .params(
                  op.id(),
                  payment,
                  op.request(),
                  amount,
                  pending ? "PENDING" : "SUCCEEDED",
                  op.reference(),
                  pending ? null : day.atTime(10, 0).atOffset(ZoneOffset.UTC))
              .update();
          if (!pending) {
            ledger.post(
                op.id(),
                EntryType.CAPTURE,
                PostingRules.capture(
                    "mock-psp", merchant, Money.of(amount, Currency.getInstance("GBP")), 100));
          }
        });
    return op;
  }

  private String row(Op op, String merchant, LocalDate day, long amount) {
    return String.join(
            ",",
            op.reference(),
            op.request(),
            "CAPTURE",
            merchant,
            Long.toString(amount),
            "GBP",
            day + "T10:00:00Z")
        + "\r\n";
  }
}
