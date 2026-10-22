package com.ledgerpay.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.money.Fees;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.common.webhook.WebhookSignature;
import com.ledgerpay.payment.api.ApiKeys;
import com.ledgerpay.payment.ledger.integrity.IntegrityResult;
import com.ledgerpay.payment.ledger.integrity.LedgerIntegrityCheck;
import com.ledgerpay.payment.psp.PspOperationWorker;
import com.ledgerpay.payment.psp.StubPsp;
import com.ledgerpay.payment.psp.StubPsp.Reply;
import com.ledgerpay.payment.webhook.InboxProcessor;
import com.ledgerpay.payment.webhook.SignedWebhooks;
import com.ledgerpay.payment.webhook.SignedWebhooks.Event;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.statistics.Statistics;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Random payment histories against the real service (design §12, #20): create, authorise, capture,
 * void, partial refunds one at a time or two at once, with every PSP outcome delivered a random way
 * (in the submit response, by webhook, duplicated, followed by a stale event, after a timeout and
 * an inquiry, or before the call). After each history the books must hold every invariant:
 *
 * <ul>
 *   <li>the ledger integrity check passes: one balanced journal per settled operation, each booking
 *       exactly its operation, every currency summing to zero, payment totals equal to journals;
 *   <li>refunded + reserved ≤ captured as the service reports it, checked while two refunds are
 *       accepted but not yet settled, and nothing is left reserved once every outcome is in;
 *   <li>every answer, status, journal count and the merchant's balance are what a simple model of
 *       the history predicts.
 * </ul>
 *
 * <p>jqwik runs 1,000 histories by default ({@code jqwik.tries.default}); CI passes fewer. A
 * failure prints its seed and the shrunk history; {@code @Property(seed = "…")} replays it.
 *
 * <p>jqwik properties do not run under Spring's JUnit extension, so the application is started
 * here, once, on a PostgreSQL container of its own, which is emptied before each history: the
 * integrity check reads the whole ledger, and must see only this history.
 */
class PaymentHistoryPropertyTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final StubPsp PSP = new StubPsp();
  static final int FEE_BPS = 100;
  static final Pattern REQUEST_ID = Pattern.compile("\"psp_request_id\"\\s*:\\s*\"([^\"]+)\"");

  static ConfigurableApplicationContext context;
  static int port;
  static JdbcClient jdbc;
  static ObjectMapper json;
  static PspOperationWorker worker;
  static InboxProcessor inbox;
  static LedgerIntegrityCheck integrity;
  static TransactionTemplate transactions;

  /** What the PSP says about each request ID, set before the worker calls it. */
  static final Map<String, Answer> ANSWERS = new ConcurrentHashMap<>();

  final HttpClient http = HttpClient.newHttpClient();
  String merchant;
  String apiKey;

  /** How each pair of concurrent refunds was answered, for the statistics. */
  final List<String> concurrency = new ArrayList<>();

  // --- the histories ------------------------------------------------------------------------

  /** How an operation's outcome reaches us. */
  enum Delivery {
    /** In the response to the submit. */
    SUBMIT_RESPONSE,
    /** Accepted as PENDING, then one webhook. */
    WEBHOOK,
    /** Accepted, then the outcome several times under one event ID and once under another. */
    DUPLICATE_WEBHOOKS,
    /** Accepted, the outcome, then an older PENDING event (resource version 1). */
    STALE_EVENT_AFTER,
    /** The submit times out (503), and the worker's next attempt inquires and finds it. */
    TIMEOUT_THEN_INQUIRY,
    /** The webhook arrives before the worker has even called the PSP. */
    WEBHOOK_BEFORE_SUBMIT
  }

  record Outcome(boolean succeeds, Delivery delivery) {
    String status() {
      return succeeds ? "SUCCEEDED" : "FAILED";
    }
  }

  sealed interface Step {}

  record Capture(Outcome outcome) implements Step {}

  record VoidPayment(Outcome outcome) implements Step {}

  record Refund(long amount, Outcome outcome) implements Step {}

  record ConcurrentRefunds(long first, long second, Outcome firstOutcome, Outcome secondOutcome)
      implements Step {}

  record History(long amount, Outcome authorisation, List<Step> steps) {}

  @Provide
  Arbitrary<History> histories() {
    return Arbitraries.longs()
        .between(1, 100_000)
        .flatMap(
            amount ->
                Combinators.combine(outcomes(9), firstStep(), steps(amount).list().ofMaxSize(5))
                    .as(
                        (authorisation, first, rest) -> {
                          List<Step> steps = new ArrayList<>(first);
                          steps.addAll(rest);
                          return new History(amount, authorisation, steps);
                        }));
  }

  /**
   * Mostly a capture first: with every step drawn alike, most refunds came before any capture and
   * were only ever 409s, and half the histories never moved money.
   */
  static Arbitrary<List<Step>> firstStep() {
    return Arbitraries.frequencyOf(
        Tuple.of(8, outcomes(8).map(outcome -> List.<Step>of(new Capture(outcome)))),
        Tuple.of(2, Arbitraries.just(List.<Step>of())));
  }

  /** Succeeds {@code successWeight} times in 10. */
  static Arbitrary<Outcome> outcomes(int successWeight) {
    return Combinators.combine(
            Arbitraries.frequency(
                Tuple.of(successWeight, true), Tuple.of(10 - successWeight, false)),
            Arbitraries.of(Delivery.class))
        .as(Outcome::new);
  }

  static Arbitrary<Step> steps(long amount) {
    Arbitrary<Long> part = Arbitraries.longs().between(1, amount);
    return Arbitraries.frequencyOf(
        Tuple.of(4, outcomes(8).map(Capture::new)),
        Tuple.of(1, outcomes(8).map(VoidPayment::new)),
        Tuple.of(4, Combinators.combine(part, outcomes(7)).as(Refund::new)),
        Tuple.of(
            2,
            Combinators.combine(part, part, outcomes(7), outcomes(7)).as(ConcurrentRefunds::new)));
  }

  // --- the model ----------------------------------------------------------------------------

  /** What the books should say, kept by hand from the history's outcomes. */
  static final class Model {
    final long amount;
    String status = "AUTH_PENDING";
    long refunded;
    long settledOperations;
    int refundsBooked;

    Model(long amount) {
      this.amount = amount;
    }

    long captured() {
      return status.equals("CAPTURED") ? amount : 0;
    }

    long refundable() {
      return captured() - refunded;
    }

    /** merchant_payable, debit positive: the share owed for the capture, less what was refunded. */
    long merchantBalance() {
      if (captured() == 0) {
        return 0;
      }
      long fee = Fees.calculate(Money.of(amount, Money.GBP), FEE_BPS).minor();
      return -(amount - fee) + refunded;
    }
  }

  @Property
  void everyHistoryLeavesTheBooksConsistent(@ForAll("histories") History history) throws Exception {
    var model = new Model(history.amount());

    UUID payment = createPayment(history.amount());
    settle(requestId(payment, "AUTHORIZE"), "AUTHORIZE", history.amount(), history.authorisation());
    model.status = history.authorisation().succeeds() ? "AUTHORIZED" : "DECLINED";
    expectStatus(payment, model);

    for (Step step : history.steps()) {
      switch (step) {
        case Capture capture ->
            transition(payment, "capture", "CAPTURE", capture.outcome(), "CAPTURED", model);
        case VoidPayment voidPayment ->
            transition(payment, "void", "VOID", voidPayment.outcome(), "VOIDED", model);
        case Refund refund -> refundOne(payment, refund.amount(), refund.outcome(), model);
        case ConcurrentRefunds both -> refundTwoAtOnce(payment, both, model);
      }
      expectStatus(payment, model);
    }

    JsonNode view = get("/v1/payments/" + payment);
    assertThat(view.path("capturedMinor").asLong()).as("captured").isEqualTo(model.captured());
    assertThat(view.path("refundedMinor").asLong()).as("refunded").isEqualTo(model.refunded);
    assertThat(view.path("refundReservedMinor").asLong()).as("left reserved").isZero();
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM journal_entries j
                      JOIN psp_operations o ON o.id = j.psp_operation_id WHERE o.payment_id = ?""")
                .param(payment)
                .query(Long.class)
                .single())
        .as("journals")
        .isEqualTo(model.settledOperations);
    assertThat(
            get("/v1/accounts/merchant_payable:" + merchant + "/balance")
                .path("balanceMinor")
                .asLong())
        .as("merchant balance")
        .isEqualTo(model.merchantBalance());
    IntegrityResult books = integrity.run();
    assertThat(books.violations()).as("ledger integrity").isEmpty();

    // What the run covered, printed with its results: the histories must reach the paths that
    // matter, not just pass.
    Statistics.label("final status").collect(model.status);
    Statistics.label("refunds booked").collect(Math.min(model.refundsBooked, 3));
    history.steps().stream()
        .filter(ConcurrentRefunds.class::isInstance)
        .forEach(step -> Statistics.label("concurrent refunds").collect(concurrency.removeFirst()));
  }

  /** A capture or void: allowed only from AUTHORIZED; a rejection returns it there. */
  void transition(
      UUID payment, String action, String type, Outcome outcome, String success, Model model)
      throws Exception {
    Response response = post("/v1/payments/" + payment + "/" + action, null);
    if (!model.status.equals("AUTHORIZED")) {
      assertThat(response.status()).as(action + " from " + model.status).isEqualTo(409);
      assertThat(response.code()).isEqualTo("INVALID_STATE");
      return;
    }
    assertThat(response.status()).as(action).isEqualTo(202);
    settle(requestId(payment, type), type, model.amount, outcome);
    if (outcome.succeeds()) {
      model.status = success;
      if (type.equals("CAPTURE")) {
        model.settledOperations++;
      }
    }
  }

  void refundOne(UUID payment, long amount, Outcome outcome, Model model) throws Exception {
    Response response = refund(payment, amount);
    if (!model.status.equals("CAPTURED")) {
      assertThat(response.status()).as("refund from " + model.status).isEqualTo(409);
      assertThat(response.code()).isEqualTo("INVALID_STATE");
      return;
    }
    if (amount > model.refundable()) {
      assertThat(response.status()).as("refund over " + model.refundable()).isEqualTo(409);
      assertThat(response.code()).isEqualTo("REFUND_AMOUNT_EXCEEDED");
      return;
    }
    assertThat(response.status()).as("refund of " + amount).isEqualTo(202);
    settleRefund(response, amount, outcome, model);
  }

  /**
   * Two refunds at once: the payment lock makes them one after the other, so they are accepted as
   * if in some order. Both fit, both are accepted; only one fits on its own, only that one; each
   * fits but not together, exactly one, either.
   */
  void refundTwoAtOnce(UUID payment, ConcurrentRefunds both, Model model) throws Exception {
    CompletableFuture<Response> first =
        CompletableFuture.supplyAsync(() -> refund(payment, both.first()));
    CompletableFuture<Response> second =
        CompletableFuture.supplyAsync(() -> refund(payment, both.second()));
    Response a = first.join();
    Response b = second.join();
    if (!model.status.equals("CAPTURED")) {
      assertThat(List.of(a, b))
          .allSatisfy(
              refused -> {
                assertThat(refused.status()).isEqualTo(409);
                assertThat(refused.code()).isEqualTo("INVALID_STATE");
              });
      concurrency.add("not captured");
      return;
    }
    long left = model.refundable();
    boolean firstFits = both.first() <= left;
    boolean secondFits = both.second() <= left;
    if (both.first() + both.second() <= left) {
      assertThat(List.of(a.status(), b.status())).as("both fit in " + left).containsOnly(202);
    } else if (firstFits && secondFits) {
      assertThat(List.of(a.status(), b.status()))
          .as("each fits in " + left + ", not both")
          .containsExactlyInAnyOrder(202, 409);
    } else {
      assertThat(a.status()).as("first alone").isEqualTo(firstFits ? 202 : 409);
      assertThat(b.status()).as("second alone").isEqualTo(secondFits ? 202 : 409);
    }
    concurrency.add(
        a.status() == 202 && b.status() == 202
            ? "both accepted"
            : a.status() == 202 || b.status() == 202 ? "one accepted, one 409" : "both 409");
    for (Response refused : List.of(a, b)) {
      if (refused.status() == 409) {
        assertThat(refused.code()).isEqualTo("REFUND_AMOUNT_EXCEEDED");
      }
    }
    // Both answered, neither settled: the service holds exactly the accepted amounts in reserve,
    // and refunded + reserved stays within captured. Read from the service, not the model.
    long accepted =
        (a.status() == 202 ? both.first() : 0) + (b.status() == 202 ? both.second() : 0);
    JsonNode inFlight = get("/v1/payments/" + payment);
    assertThat(inFlight.path("refundReservedMinor").asLong())
        .as("reserved while in flight")
        .isEqualTo(accepted);
    assertThat(
            inFlight.path("refundedMinor").asLong() + inFlight.path("refundReservedMinor").asLong())
        .as("refunded + reserved")
        .isLessThanOrEqualTo(inFlight.path("capturedMinor").asLong());
    if (a.status() == 202) {
      settleRefund(a, both.first(), both.firstOutcome(), model);
    }
    if (b.status() == 202) {
      settleRefund(b, both.second(), both.secondOutcome(), model);
    }
  }

  void settleRefund(Response accepted, long amount, Outcome outcome, Model model) {
    UUID refundId = UUID.fromString(accepted.body().path("refundId").asText());
    String requestId =
        jdbc.sql("SELECT psp_request_id FROM psp_operations WHERE refund_id = ?")
            .param(refundId)
            .query(String.class)
            .single();
    settle(requestId, "REFUND", amount, outcome);
    String status =
        jdbc.sql("SELECT status FROM refunds WHERE id = ?")
            .param(refundId)
            .query(String.class)
            .single();
    assertThat(status).as("refund").isEqualTo(outcome.status());
    if (outcome.succeeds()) {
      model.refunded += amount;
      model.settledOperations++;
      model.refundsBooked++;
    }
  }

  // --- driving the PSP side -----------------------------------------------------------------

  /** How the stub PSP answers a submit and an inquiry for one request ID. */
  record Answer(Reply submit, Reply inquiry) {}

  /**
   * Brings the operation to its outcome the way the delivery says, and checks it is final. Time
   * does not pass in a test: an operation that must be retried is made due by hand.
   */
  void settle(String requestId, String type, long amount, Outcome outcome) {
    Statistics.label("deliveries").collect(outcome.delivery());
    String reference = "psp_" + requestId;
    Reply pending = Reply.of(201, StubPsp.operation(requestId, reference, "PENDING"));
    Reply finalAnswer = Reply.of(201, StubPsp.operation(requestId, reference, outcome.status()));
    Reply found = Reply.of(200, StubPsp.operation(requestId, reference, outcome.status()));
    Event event = Event.of(type, requestId, merchant, amount, outcome.status());
    // Only this operation is due, so the worker calls nothing else meanwhile: the other of two
    // concurrent refunds waits for its own turn and its own answer.
    jdbc.sql(
            """
            UPDATE psp_operations
               SET next_attempt_at = CASE WHEN psp_request_id = ? THEN now()
                                          ELSE now() + interval '1 day' END
             WHERE status = 'PENDING'""")
        .param(requestId)
        .update();
    switch (outcome.delivery()) {
      case SUBMIT_RESPONSE -> {
        ANSWERS.put(requestId, new Answer(finalAnswer, found));
        worker.drain();
      }
      case WEBHOOK -> {
        ANSWERS.put(requestId, new Answer(pending, found));
        worker.drain();
        deliver(event);
      }
      case DUPLICATE_WEBHOOKS -> {
        ANSWERS.put(requestId, new Answer(pending, found));
        worker.drain();
        for (int i = 0; i < 3; i++) {
          deliver(event);
        }
        deliver(event.withEventId("evt_" + UUID.randomUUID()));
      }
      case STALE_EVENT_AFTER -> {
        ANSWERS.put(requestId, new Answer(pending, found));
        worker.drain();
        deliver(event);
        deliver(Event.of(type, requestId, merchant, amount, "PENDING").withResourceVersion(1));
      }
      case TIMEOUT_THEN_INQUIRY -> {
        ANSWERS.put(
            requestId,
            new Answer(
                Reply.of(503, "{\"code\": \"UNAVAILABLE\"}"),
                Reply.of(404, "{\"code\": \"NOT_FOUND\"}")));
        worker.drain();
        // The PSP had it after all: the retry's inquiry finds the outcome, and nothing is
        // resubmitted.
        ANSWERS.put(requestId, new Answer(Reply.of(500, "{}"), found));
        makeDue(requestId);
        worker.drain();
      }
      case WEBHOOK_BEFORE_SUBMIT -> {
        ANSWERS.put(requestId, new Answer(finalAnswer, found));
        deliver(event);
        worker.drain();
      }
    }
    String status =
        jdbc.sql("SELECT status FROM psp_operations WHERE psp_request_id = ?")
            .param(requestId)
            .query(String.class)
            .single();
    assertThat(status).as(type + " " + outcome).isEqualTo(outcome.status());
  }

  void makeDue(String requestId) {
    jdbc.sql("UPDATE psp_operations SET next_attempt_at = now() WHERE psp_request_id = ?")
        .param(requestId)
        .update();
  }

  void deliver(Event event) {
    String body = event.json();
    long timestamp = Instant.now().getEpochSecond();
    String signature =
        WebhookSignature.sign(
            SignedWebhooks.SECRET.getBytes(StandardCharsets.UTF_8),
            timestamp,
            body.getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/psp"))
            .header("Content-Type", "application/json")
            .header(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(timestamp))
            .header(WebhookSignature.SIGNATURE_HEADER, signature)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(send(request).status()).as("webhook").isEqualTo(200);
    inbox.processAll();
  }

  static Reply answer(StubPsp.Request request) {
    String requestId;
    if (request.isSubmit()) {
      Matcher matcher = REQUEST_ID.matcher(request.body());
      requestId = matcher.find() ? matcher.group(1) : "";
    } else {
      requestId = request.path().substring(request.path().lastIndexOf('/') + 1);
    }
    Answer answer = ANSWERS.get(requestId);
    if (answer == null) {
      return Reply.of(500, "{\"code\": \"NO_ANSWER_SET\"}");
    }
    return request.isSubmit() ? answer.submit() : answer.inquiry();
  }

  // --- the API ------------------------------------------------------------------------------

  record Response(int status, JsonNode body) {
    String code() {
      return body.path("code").asText();
    }
  }

  UUID createPayment(long amount) throws Exception {
    Response created =
        post(
            "/v1/payments",
            """
            {"merchantReference": "order-%s", "amountMinor": %d, "currency": "GBP"}"""
                .formatted(UUID.randomUUID(), amount));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(202);
    return UUID.fromString(created.body().path("paymentId").asText());
  }

  Response refund(UUID payment, long amount) {
    return post(
        "/v1/payments/" + payment + "/refunds",
        """
        {"merchantReference": "refund-%s", "amountMinor": %d, "reason": "property"}"""
            .formatted(UUID.randomUUID(), amount));
  }

  String requestId(UUID payment, String type) {
    return jdbc.sql(
            """
            SELECT psp_request_id FROM psp_operations
             WHERE payment_id = ? AND type = ? AND status = 'PENDING'""")
        .params(payment, type)
        .query(String.class)
        .single();
  }

  void expectStatus(UUID payment, Model model) throws Exception {
    assertThat(get("/v1/payments/" + payment).path("status").asText())
        .as("payment status")
        .isEqualTo(model.status);
  }

  Response post(String path, String body) {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + apiKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "application/json")
            .POST(
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return send(request);
  }

  JsonNode get(String path) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + apiKey)
            .build();
    Response response = send(request);
    assertThat(response.status()).as(path).isEqualTo(200);
    return response.body();
  }

  Response send(HttpRequest request) {
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      String body = response.body();
      return new Response(response.statusCode(), json.readTree(body.isEmpty() ? "{}" : body));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  // --- lifecycle ----------------------------------------------------------------------------

  @BeforeContainer
  static void start() {
    POSTGRES.start();
    PSP.respond(PaymentHistoryPropertyTest::answer);
    context =
        new SpringApplicationBuilder(PaymentServiceApplication.class)
            // Arguments, not .properties(): those are defaults, below application.yml.
            .run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--ledgerpay.psp.base-url=" + PSP.url(),
                "--ledgerpay.psp.webhook-secret=" + SignedWebhooks.SECRET,
                "--ledgerpay.recon.sweeper.enabled=false",
                "--logging.level.com.ledgerpay=WARN");
    port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    jdbc = context.getBean(JdbcClient.class);
    json = context.getBean(ObjectMapper.class);
    worker = context.getBean(PspOperationWorker.class);
    inbox = context.getBean(InboxProcessor.class);
    integrity = context.getBean(LedgerIntegrityCheck.class);
    transactions = context.getBean(TransactionTemplate.class);
  }

  @AfterContainer
  static void stop() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  /** An empty database and a new merchant for each history, triggers off only to empty it. */
  @BeforeTry
  void emptyBooks() {
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL session_replication_role = replica").update();
          jdbc.sql(
                  "TRUNCATE merchants, settlement_reports, ledger_integrity_checks, accounts,"
                      + " outbox_events, inbox_events, idempotency_keys RESTART IDENTITY CASCADE")
              .update();
        });
    ANSWERS.clear();
    // Requests are recorded for other tests' assertions; this one never reads them, so they would
    // only pile up over a thousand histories. reset() also drops the responder: set it again.
    PSP.reset();
    PSP.respond(PaymentHistoryPropertyTest::answer);
    concurrency.clear();
    merchant = "m_" + UUID.randomUUID();
    apiKey = "mk_test_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash, fee_bps) VALUES (?, ?, ?)")
        .params(merchant, ApiKeys.hash(apiKey), FEE_BPS)
        .update();
  }
}
