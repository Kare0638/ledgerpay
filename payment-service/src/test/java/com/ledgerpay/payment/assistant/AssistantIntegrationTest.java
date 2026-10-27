package com.ledgerpay.payment.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.TestDays;
import com.ledgerpay.payment.settlement.SettlementImporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The reconciliation assistant against a scripted Gemini (design §16, #26): what reaches the model,
 * where the key goes, the guardrails on proposals and output, and the human confirmation. The
 * breaks are made by the real reconciliation of a report; only the model is a stub.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledgerpay.ops.api-key-hash=" + AssistantIntegrationTest.OPS_KEY_HASH,
      "ledgerpay.assistant.api-key=" + AssistantIntegrationTest.KEY,
      "ledgerpay.assistant.max-turns=6",
      "ledgerpay.assistant.max-retries=1",
      "ledgerpay.assistant.retry-backoff=10ms",
      "ledgerpay.recon.sweeper.enabled=false"
    })
@Import(PostgresTestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AssistantIntegrationTest {

  static final String OPS_KEY = "ops_test_key";
  static final String OPS_KEY_HASH =
      "17da52acda373b82d53e6601abffb233bc9a735579df11d2c78596cfa5890134";

  /** Not a real key: an opaque test value, which must still never leave the header. */
  static final String KEY = "test-gemini-key-not-real-7f3a";

  static final String HEADER =
      "psp_reference,psp_request_id,txn_type,merchant_id,amount_minor,currency,succeeded_at\r\n";

  static final StubGemini gemini = new StubGemini();

  @DynamicPropertySource
  static void geminiUrl(DynamicPropertyRegistry registry) {
    registry.add("ledgerpay.assistant.base-url", gemini::url);
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired SettlementImporter importer;

  final HttpClient http = HttpClient.newHttpClient();
  String merchant;
  LocalDate day;
  UUID reportId;
  UUID runId;

  /** P1 captured and in the report; P2 captured at the PSP, PENDING here (a lost webhook). */
  Op p1;

  Op p2;

  record Op(UUID id, UUID paymentId, String reference, String requestId, long amount) {}

  record Response(int status, JsonNode body) {}

  @BeforeEach
  void aDayWithALostWebhook() throws Exception {
    gemini.reset();
    merchant = "m_" + UUID.randomUUID();
    day = TestDays.next();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, "h_" + merchant)
        .update();
    p1 = operation(10_000, "SUCCEEDED");
    p2 = operation(20_000, "PENDING");
    String csv = HEADER + row(p1) + row(p2);
    reportId =
        importer
            .importReport(
                "psp-" + UUID.randomUUID().toString().substring(0, 8),
                day,
                csv.getBytes(StandardCharsets.UTF_8))
            .report()
            .reportId();
    runTheReport();
  }

  /** Reconciles the day's report, again if it was before: a new run with the same breaks. */
  void runTheReport() throws Exception {
    runId =
        UUID.fromString(
            send("POST", "/ops/recon-runs", "{\"reportId\": \"" + reportId + "\"}")
                .body()
                .path("runId")
                .asText());
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                send("GET", "/ops/recon-runs/" + runId, null)
                    .body()
                    .path("status")
                    .asText()
                    .equals("COMPLETED"));
  }

  Op operation(long amount, String status) {
    UUID payment = UUID.randomUUID();
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
            status.equals("SUCCEEDED") ? "CAPTURED" : "CAPTURE_PENDING",
            status.equals("SUCCEEDED") ? amount : 0)
        .update();
    var op =
        new Op(
            UUID.randomUUID(),
            payment,
            "psp_" + UUID.randomUUID(),
            "req_" + UUID.randomUUID(),
            amount);
    jdbc.sql(
            """
            INSERT INTO psp_operations (id, payment_id, type, psp_request_id, amount_minor,
                                        currency, status, psp_reference, succeeded_at,
                                        next_attempt_at)
            VALUES (?, ?, 'CAPTURE', ?, ?, 'GBP', ?, ?, ?, now() + interval '15 minutes')""")
        .params(
            op.id(),
            payment,
            op.requestId(),
            amount,
            status,
            op.reference(),
            status.equals("SUCCEEDED") ? day.atTime(10, 0).atOffset(ZoneOffset.UTC) : null)
        .update();
    return op;
  }

  String row(Op op) {
    return String.join(
            ",",
            op.reference(),
            op.requestId(),
            "CAPTURE",
            merchant,
            Long.toString(op.amount()),
            "GBP",
            day + "T10:00:00Z")
        + "\r\n";
  }

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
    return new Response(response.statusCode(), json.readTree(text.isEmpty() ? "{}" : text));
  }

  /** Item 2: P2's STATUS_MISMATCH (item 1 is P1, MATCHED). */
  Response analyse() throws Exception {
    return send("POST", "/ops/recon-runs/" + runId + "/items/2/analysis", null);
  }

  String submit(String cause, String explanation) {
    return """
        {"likelyCause": "%s", "confidence": "HIGH", "explanation": "%s",
         "evidence": ["The PSP report lists %s as a succeeded CAPTURE"],
         "nextSteps": ["Confirm the proposed inquiry"]}"""
        .formatted(cause, explanation, p2.reference());
  }

  /** One turn, as the facts come with the question; the model submits before it proposes. */
  void scriptTheLostWebhookAnalysis() {
    gemini.thenCalls(
        "submit_analysis",
        submit(
            "OUTCOME_NOT_RECEIVED",
            "Operation " + p2.id() + " is PENDING here and no webhook was received."),
        "propose_inquiry",
        "{\"pspOperationId\": \"" + p2.id() + "\", \"reason\": \"The PSP says SUCCEEDED\"}");
  }

  /** The question of the generateContent call {@code index}. */
  String question(int index) throws Exception {
    return json.readTree(gemini.calls().get(index).body())
        .path("contents")
        .path(0)
        .path("parts")
        .path(0)
        .path("text")
        .asText();
  }

  /** Analyses this day's lost webhook and a person confirms the inquiry; the analysis' ID. */
  String analyseAndConfirm() throws Exception {
    scriptTheLostWebhookAnalysis();
    JsonNode stored = analyse().body();
    send(
        "POST",
        "/ops/assistant/proposals/"
            + stored.path("proposal").path("proposalId").asText()
            + "/confirm",
        "{\"decidedBy\": \"ops-ana\"}");
    return stored.path("analysisId").asText();
  }

  @Test
  void aLostWebhookIsAnalysedFromTheToolsFactsAndTheKeyOnlyEverTravelsInAHeader(
      CapturedOutput output) throws Exception {
    scriptTheLostWebhookAnalysis();

    Response response = analyse();

    assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
    JsonNode stored = response.body();
    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("model").asText()).isEqualTo("gemini-3.5-flash");
    assertThat(stored.path("analysis").path("likelyCause").asText())
        .isEqualTo("OUTCOME_NOT_RECEIVED");
    assertThat(stored.path("proposal").path("status").asText()).isEqualTo("PROPOSED");
    assertThat(stored.path("proposal").path("pspOperationId").asText())
        .isEqualTo(p2.id().toString());
    assertThat(stored.path("turns").asInt()).isEqualTo(1);
    assertThat(stored.path("tokens").asInt()).isEqualTo(100);
    // The proposal was recorded although the model made it after submitting.
    assertThat(stored.path("toolCalls").findValuesAsText("tool"))
        .containsExactly("propose_inquiry", "submit_analysis");

    var calls = gemini.calls();
    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().uri()).isEqualTo("/v1beta/models/gemini-3.5-flash:generateContent");
    // The break was embedded first, to find precedents (#64), with the key in the same place.
    var embedCalls = gemini.embedCalls();
    assertThat(embedCalls).hasSize(1);
    assertThat(embedCalls.getFirst().uri())
        .isEqualTo("/v1beta/models/gemini-embedding-001:embedContent");
    for (var call : Stream.concat(calls.stream(), embedCalls.stream()).toList()) {
      assertThat(call.header("x-goog-api-key")).isEqualTo(KEY);
      assertThat(call.uri()).doesNotContain(KEY);
      assertThat(call.body()).doesNotContain(KEY);
    }
    // The break's facts and its operation's timeline came with the question.
    String question =
        json.readTree(calls.get(0).body())
            .path("contents")
            .path(0)
            .path("parts")
            .path(0)
            .path("text")
            .asText();
    JsonNode given = json.readTree(question.substring(question.indexOf('{')));
    JsonNode breakFacts = given.path("get_break");
    assertThat(breakFacts.path("category").asText()).isEqualTo("STATUS_MISMATCH");
    assertThat(breakFacts.path("reportLines").get(0).path("pspReference").asText())
        .isEqualTo(p2.reference());
    JsonNode timeline = given.path("get_operation_timeline");
    assertThat(timeline.path("operation").path("status").asText()).isEqualTo("PENDING");
    assertThat(timeline.path("webhooksReceived")).isEmpty();
    assertThat(timeline.path("journals")).isEmpty();

    assertThat(output.getAll()).doesNotContain(KEY);
    assertThat(stored.toString()).doesNotContain(KEY);
    assertThat(
            send("GET", "/ops/assistant/analyses/" + stored.path("analysisId").asText(), null)
                .body())
        .isEqualTo(stored);
  }

  @Test
  void onlyAPersonsConfirmationRequestsTheInquiryAndOnlyOnce() throws Exception {
    scriptTheLostWebhookAnalysis();
    String proposal = analyse().body().path("proposal").path("proposalId").asText();
    // Proposing changed nothing: the operation is not due before its safety net.
    assertThat(dueNow(p2.id())).isFalse();

    Response confirmed =
        send(
            "POST",
            "/ops/assistant/proposals/" + proposal + "/confirm",
            "{\"decidedBy\": \"ops-ana\"}");

    assertThat(confirmed.status()).isEqualTo(200);
    assertThat(confirmed.body().path("proposal").path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(confirmed.body().path("proposal").path("outcome").asText()).isEqualTo("SCHEDULED");
    assertThat(confirmed.body().path("proposal").path("decidedBy").asText()).isEqualTo("ops-ana");
    assertThat(dueNow(p2.id())).isTrue();
    assertThat(
            send(
                    "POST",
                    "/ops/assistant/proposals/" + proposal + "/confirm",
                    "{\"decidedBy\": \"x\"}")
                .status())
        .isEqualTo(409);
    assertThat(
            send(
                    "POST",
                    "/ops/assistant/proposals/" + proposal + "/reject",
                    "{\"decidedBy\": \"x\"}")
                .status())
        .isEqualTo(409);
    assertThat(send("POST", "/ops/assistant/proposals/" + proposal + "/confirm", "{}").status())
        .isEqualTo(400);
  }

  @Test
  void aRejectedProposalRequestsNothing() throws Exception {
    scriptTheLostWebhookAnalysis();
    String proposal = analyse().body().path("proposal").path("proposalId").asText();

    Response rejected =
        send(
            "POST",
            "/ops/assistant/proposals/" + proposal + "/reject",
            "{\"decidedBy\": \"ops-ben\"}");

    assertThat(rejected.body().path("proposal").path("status").asText()).isEqualTo("REJECTED");
    assertThat(dueNow(p2.id())).isFalse();
  }

  boolean dueNow(UUID operation) {
    return jdbc.sql("SELECT next_attempt_at <= now() FROM psp_operations WHERE id = ?")
        .param(operation)
        .query(Boolean.class)
        .single();
  }

  @Test
  void toolFactsGoToTheModelAndItsTurnsGoBackAsTheyCame() throws Exception {
    gemini
        .thenCall("get_operation_timeline", "{\"pspOperationId\": \"" + p2.id() + "\"}")
        .thenCall(
            "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));

    assertThat(analyse().body().path("status").asText()).isEqualTo("COMPLETED");

    JsonNode second = json.readTree(gemini.calls().get(1).body()).path("contents");
    // Signatures included: Gemini's thinking models need their own parts back.
    assertThat(second.path(1).path("parts").path(0).path("thoughtSignature").asText())
        .isEqualTo("sig-1");
    JsonNode timeline =
        second.path(2).path("parts").path(0).path("functionResponse").path("response");
    assertThat(timeline.path("operation").path("status").asText()).isEqualTo("PENDING");
  }

  @Test
  void aProposalForAnotherOperationIsRefusedAndTheModelIsTold() throws Exception {
    gemini
        .thenCall("get_break", "{}")
        .thenCall(
            "propose_inquiry", "{\"pspOperationId\": \"" + p1.id() + "\", \"reason\": \"why not\"}")
        .thenCall(
            "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));

    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("proposal").isNull()).isTrue();
    assertThat(stored.path("toolCalls").get(1).path("ok").asBoolean()).isFalse();
    String refusal =
        json.readTree(gemini.calls().get(2).body())
            .path("contents")
            .path(4)
            .path("parts")
            .path(0)
            .path("functionResponse")
            .path("response")
            .path("error")
            .asText();
    assertThat(refusal).startsWith("only this break's own operation can be inquired about");
  }

  @Test
  void anInventedIdIsSentBackOnceAndThenTheCorrectedAnalysisStands() throws Exception {
    gemini
        .thenCall("get_break", "{}")
        .thenCall(
            "submit_analysis",
            submit(
                "OUTCOME_NOT_RECEIVED", "Payment 11111111-2222-3333-4444-555555555555 is stuck."))
        .thenCall(
            "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));

    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("toolCalls").findValuesAsText("ok"))
        .containsExactly("true", "false", "true");
  }

  /** Looking an invented ID up does not make it citable: no reply repeats what it was asked. */
  @Test
  void anInventedIdLookedUpIsStillRefused() throws Exception {
    String invented = "psp_invented_7";
    UUID inventedOperation = UUID.fromString("11111111-2222-3333-4444-555555555555");
    gemini
        .thenCall("find_operations", "{\"reference\": \"" + invented + "\"}")
        .thenCall("get_operation_timeline", "{\"pspOperationId\": \"" + inventedOperation + "\"}")
        .thenCall(
            "submit_analysis",
            submit(
                "OUTCOME_NOT_RECEIVED",
                "Operation " + inventedOperation + " under " + invented + " is stuck."))
        .thenCall(
            "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));

    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("toolCalls").findValuesAsText("ok"))
        .containsExactly("true", "true", "false", "true");
    String refusal =
        json.readTree(gemini.calls().get(3).body())
            .path("contents")
            .path(6)
            .path("parts")
            .path(0)
            .path("functionResponse")
            .path("response")
            .path("error")
            .asText();
    assertThat(refusal).contains(invented, inventedOperation.toString());
  }

  /** The reason is what the person deciding reads: it may cite only IDs from the facts too. */
  @Test
  void aProposalWhoseReasonCitesAnInventedIdIsRefused() throws Exception {
    gemini
        .thenCall(
            "propose_inquiry",
            "{\"pspOperationId\": \""
                + p2.id()
                + "\", \"reason\": \"The PSP settled it as psp_invented_9\"}")
        .thenCall(
            "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));

    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("proposal").isNull()).isTrue();
    assertThat(stored.path("toolCalls").get(0).path("ok").asBoolean()).isFalse();
    String refusal =
        json.readTree(gemini.calls().get(1).body())
            .path("contents")
            .path(2)
            .path("parts")
            .path(0)
            .path("functionResponse")
            .path("response")
            .path("error")
            .asText();
    assertThat(refusal).startsWith("the reason cites IDs the tools never returned: psp_invented_9");
  }

  /** #64: a confirmed analysis of another day's lost webhook comes with the question, IDs out. */
  @Test
  void aConfirmedAnalysisOfAnotherBreakIsGivenAsAPrecedentWithoutItsIds() throws Exception {
    Op earlier = p2;
    String precedent = analyseAndConfirm();
    aDayWithALostWebhook();
    scriptTheLostWebhookAnalysis();

    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    // Breaks of the same kind tie on distance; the newest comes first.
    assertThat(stored.path("precedentIds").get(0).asText()).isEqualTo(precedent);
    String question = question(0);
    // Its kind and verdict, not the model's free text about the other break.
    assertThat(question.substring(question.indexOf("Precedents:")))
        .contains(
            "category: STATUS_MISMATCH",
            "\"likelyCause\" : \"OUTCOME_NOT_RECEIVED\"",
            "\"inquiryConfirmedByAPerson\" : true")
        .doesNotContain(
            earlier.id().toString(),
            earlier.reference(),
            earlier.requestId(),
            "no webhook was received",
            "Confirm the proposed inquiry");
    assertThat(
            send("GET", "/ops/assistant/analyses/" + stored.path("analysisId").asText(), null)
                .body()
                .path("precedentIds"))
        .isEqualTo(stored.path("precedentIds"));
  }

  /**
   * Only an analysis nobody overruled is a precedent: not one whose proposal is still open or was
   * rejected, and not one a newer analysis of the same break replaced.
   */
  @Test
  void anOpenOrRejectedProposalOrAReplacedAnalysisIsNoPrecedent() throws Exception {
    scriptTheLostWebhookAnalysis();
    String open = analyse().body().path("analysisId").asText();

    aDayWithALostWebhook();
    scriptTheLostWebhookAnalysis();
    JsonNode rejected = analyse().body();
    send(
        "POST",
        "/ops/assistant/proposals/"
            + rejected.path("proposal").path("proposalId").asText()
            + "/reject",
        "{\"decidedBy\": \"ops-ana\"}");

    aDayWithALostWebhook();
    String replaced = analyseAndConfirm();
    gemini.thenCall(
        "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));
    String replacing = analyse().body().path("analysisId").asText();

    aDayWithALostWebhook();
    scriptTheLostWebhookAnalysis();
    JsonNode stored = analyse().body();

    assertThat(stored.path("precedentIds").get(0).asText()).isEqualTo(replacing);
    assertThat(stored.path("precedentIds").toString())
        .doesNotContain(open, rejected.path("analysisId").asText(), replaced);
  }

  /**
   * A break analysed in an earlier run of the same report is the same break, not a precedent of it:
   * its own earlier answer, shown as another break's, would only agree with itself.
   */
  @Test
  void theSameBreakInAnotherRunIsNoPrecedentOfItself() throws Exception {
    gemini.thenCall(
        "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));
    String earlier = analyse().body().path("analysisId").asText();

    runTheReport();
    scriptTheLostWebhookAnalysis();
    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("precedentIds").toString()).doesNotContain(earlier);
  }

  /** A precedent's IDs are not facts of this break: citing one is refused like an invented one. */
  @Test
  void anIdFromAPrecedentCitedAsAFactIsRefused() throws Exception {
    Op earlier = p2;
    analyseAndConfirm();
    aDayWithALostWebhook();
    gemini
        .thenCall(
            "submit_analysis",
            submit("OUTCOME_NOT_RECEIVED", "Like operation " + earlier.id() + " before."))
        .thenCall(
            "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));

    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("precedentIds")).isNotEmpty();
    assertThat(stored.path("toolCalls").findValuesAsText("ok")).containsExactly("false", "true");
  }

  /** Precedents are hints: without an embedding the analysis goes ahead with none. */
  @Test
  void withoutAnEmbeddingTheAnalysisGoesAheadWithoutPrecedents() throws Exception {
    analyseAndConfirm();
    aDayWithALostWebhook();
    gemini.failEmbeddings();
    scriptTheLostWebhookAnalysis();

    JsonNode stored = analyse().body();

    assertThat(stored.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(stored.path("precedentIds")).isEmpty();
    assertThat(question(0)).doesNotContain("Precedents:");
    assertThat(
            jdbc.sql("SELECT break_embedding IS NULL FROM assistant_analyses WHERE id = ?")
                .param(UUID.fromString(stored.path("analysisId").asText()))
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void twoRefusedSubmissionsAreStoredAsInvalidOutputWithNothingToShow() throws Exception {
    gemini
        .thenCall("get_break", "{}")
        .thenCall("submit_analysis", "{\"likelyCause\": \"GREMLINS\"}")
        .thenCall("submit_analysis", "{\"likelyCause\": \"GREMLINS\"}");

    Response response = analyse();

    assertThat(response.status()).isEqualTo(201);
    assertThat(response.body().path("status").asText()).isEqualTo("INVALID_OUTPUT");
    assertThat(response.body().path("analysis").isNull()).isTrue();
    assertThat(response.body().path("error").asText()).startsWith("submit_analysis refused twice");
  }

  @Test
  void aModelThatNeverSubmitsIsGivenUpAfterMaxTurns() throws Exception {
    gemini.thenText("Thinking aloud");
    for (int i = 0; i < 5; i++) {
      gemini.thenCall("get_break", "{}");
    }

    Response response = analyse();

    assertThat(response.body().path("status").asText()).isEqualTo("FAILED");
    assertThat(response.body().path("error").asText())
        .isEqualTo("no valid analysis within 6 model calls");
    assertThat(json.readTree(gemini.calls().get(1).body()).path("contents").path(2).toString())
        .contains("finish by calling submit_analysis");
  }

  @Test
  void aRateLimitIsRetriedAndAnErrorIsA503WithTheKeyRedacted() throws Exception {
    gemini.thenStatus(429, "slow down").thenCall("get_break", "{}");
    gemini.thenCall(
        "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));
    assertThat(analyse().body().path("status").asText()).isEqualTo("COMPLETED");

    gemini.reset();
    gemini.thenStatus(400, "API key not valid: " + KEY);
    Response refused = analyse();

    assertThat(refused.status()).isEqualTo(503);
    assertThat(refused.body().path("code").asText()).isEqualTo("ASSISTANT_UNAVAILABLE");
    assertThat(refused.body().path("detail").asText())
        .isEqualTo("Gemini answered 400: ERROR API key not valid: <redacted>");
  }

  @Test
  void aDailyQuotaFailsAtOnceAndAShortWaitIsHonoured() throws Exception {
    gemini.thenQuota("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "2387s");
    Response daily = analyse();

    assertThat(daily.status()).isEqualTo(503);
    assertThat(daily.body().path("detail").asText())
        .isEqualTo(
            "Gemini's daily quota is used up (GenerateRequestsPerDayPerProjectPerModel-FreeTier);"
                + " retry in 40 min");
    assertThat(gemini.calls()).hasSize(1);

    gemini.reset();
    gemini.thenQuota("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "120s");
    assertThat(analyse().body().path("detail").asText()).endsWith("; retry in 2 min");
    assertThat(gemini.calls()).hasSize(1);

    gemini.reset();
    gemini
        .thenQuota("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "0.3s")
        .thenCall(
            "submit_analysis", submit("OUTCOME_NOT_RECEIVED", "PENDING here, SUCCEEDED there."));
    assertThat(analyse().body().path("status").asText()).isEqualTo("COMPLETED");
    assertThat(gemini.calls()).hasSize(2);
  }

  @Test
  void onlyABreakCanBeAnalysed() throws Exception {
    assertThat(send("POST", "/ops/recon-runs/" + runId + "/items/1/analysis", null).status())
        .isEqualTo(409);
    assertThat(send("POST", "/ops/recon-runs/" + runId + "/items/99/analysis", null).status())
        .isEqualTo(404);
    assertThat(gemini.calls()).isEmpty();
  }
}
