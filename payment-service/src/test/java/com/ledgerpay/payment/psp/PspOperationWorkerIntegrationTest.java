package com.ledgerpay.payment.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.payment.MoneyTransaction;
import com.ledgerpay.payment.psp.StubPsp.Reply;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link PspOperationWorker} against a scripted PSP and real PostgreSQL. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "ledgerpay.psp.read-timeout=500ms",
      "ledgerpay.psp.batch-size=5",
      // Far longer than any test: a clock step (WSL jumps about 65 s) must not expire a lease
      // mid-test and let a second worker legitimately reclaim it. Expiry is tested explicitly.
      "ledgerpay.psp.lease=10m"
    })
@Import(PostgresTestcontainersConfiguration.class)
class PspOperationWorkerIntegrationTest {

  static final StubPsp psp = new StubPsp();

  @DynamicPropertySource
  static void pspUrl(DynamicPropertyRegistry registry) {
    registry.add("ledgerpay.psp.base-url", psp::url);
  }

  @Autowired PspOperationWorker worker;
  @Autowired PspOperations operations;
  @Autowired JdbcClient jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired ObjectMapper json;
  @MockitoBean MoneyTransaction outcomes;

  String merchant;

  @BeforeEach
  void isolate() {
    psp.reset();
    merchant = "m_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, "hash_" + merchant)
        .update();
    // Operations left by earlier tests are not due during this one.
    jdbc.sql("UPDATE psp_operations SET next_attempt_at = now() + interval '1 day'").update();
  }

  record Created(UUID paymentId, UUID operationId, String pspRequestId) {}

  Created newAuthorisation(long amount) {
    UUID paymentId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO payments (id, merchant_id, merchant_reference, amount_minor, currency, status)
            VALUES (?, ?, ?, ?, 'GBP', 'AUTH_PENDING')""")
        .params(paymentId, merchant, "ref_" + paymentId, amount)
        .update();
    return pending(paymentId, PspOperationType.AUTHORIZE, amount);
  }

  Created pending(UUID paymentId, PspOperationType type, long amount) {
    UUID id =
        tx.execute(
            status -> operations.createPending(paymentId, type, Money.of(amount, Money.GBP)));
    return new Created(paymentId, id, "req_" + id);
  }

  Map<String, Object> row(UUID operationId) {
    return jdbc.sql(
            """
            SELECT status, psp_reference, attempts, last_error, needs_review,
                   lease_until IS NOT NULL AND lease_until > now() AS leased,
                   (extract(epoch FROM next_attempt_at - updated_at) * 1000)::bigint AS retry_in_ms,
                   next_attempt_at > now() + interval '14 minutes' AS safety_net_scheduled
              FROM psp_operations WHERE id = ?""")
        .param(operationId)
        .query()
        .singleRow();
  }

  JsonNode body(StubPsp.Request request) throws Exception {
    return json.readTree(request.body());
  }

  /** Answers a submit with PENDING and an inquiry with 404, like a PSP that just accepted it. */
  static Reply accepting(StubPsp.Request request) {
    if (!request.isSubmit()) {
      return Reply.of(404, "{\"code\": \"NOT_FOUND\"}");
    }
    String requestId = request.body().replaceAll("(?s).*\"psp_request_id\":\"([^\"]+)\".*", "$1");
    return Reply.of(201, StubPsp.operation(requestId, "psp_" + requestId, "PENDING"));
  }

  // --- first attempt -----------------------------------------------------------------------

  @Test
  void submitsAnAuthorisationAndKeepsTheAcceptedReference() throws Exception {
    Created op = newAuthorisation(10_000);
    psp.respond(PspOperationWorkerIntegrationTest::accepting);

    assertThat(worker.runOnce()).isEqualTo(1);

    List<StubPsp.Request> calls = psp.requestsFor(op.pspRequestId());
    assertThat(calls).hasSize(1);
    assertThat(calls.getFirst().isSubmit()).isTrue();
    JsonNode sent = body(calls.getFirst());
    assertThat(sent.path("psp_request_id").asText()).isEqualTo(op.pspRequestId());
    assertThat(sent.path("merchant_id").asText()).isEqualTo(merchant);
    assertThat(sent.path("type").asText()).isEqualTo("AUTHORIZE");
    assertThat(sent.path("parent_reference").isNull()).isTrue();
    assertThat(sent.path("amount_minor").asLong()).isEqualTo(10_000);
    assertThat(sent.path("currency").asText()).isEqualTo("GBP");

    var row = row(op.operationId());
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("psp_reference")).isEqualTo("psp_" + op.pspRequestId());
    assertThat(row.get("attempts")).isEqualTo(1);
    assertThat(row.get("leased")).isEqualTo(false);
    assertThat(row.get("safety_net_scheduled")).isEqualTo(true);
    verify(outcomes, never()).apply(any(), any());
  }

  @Test
  void captureNamesTheAuthorisationAsItsParent() throws Exception {
    Created auth = newAuthorisation(10_000);
    jdbc.sql(
            """
            UPDATE psp_operations SET status = 'SUCCEEDED', psp_reference = 'psp_auth_x',
                   succeeded_at = now() WHERE id = ?""")
        .param(auth.operationId())
        .update();
    Created capture = pending(auth.paymentId(), PspOperationType.CAPTURE, 10_000);
    psp.respond(PspOperationWorkerIntegrationTest::accepting);

    worker.runOnce();

    JsonNode sent = body(psp.requestsFor(capture.pspRequestId()).getFirst());
    assertThat(sent.path("type").asText()).isEqualTo("CAPTURE");
    assertThat(sent.path("parent_reference").asText()).isEqualTo("psp_auth_x");
  }

  @Test
  void noMoneyRowIsLockedWhileThePspIsCalled() throws Exception {
    Created op = newAuthorisation(10_000);
    List<String> lockedDuringCall = new ArrayList<>();
    psp.respond(
        request -> {
          // From the PSP's side of the call: both rows must be free to lock at once.
          for (String table : List.of("payments", "psp_operations")) {
            UUID id = table.equals("payments") ? op.paymentId() : op.operationId();
            try {
              jdbc.sql("SELECT 1 FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")
                  .param(id)
                  .query()
                  .singleRow();
            } catch (DataAccessException e) {
              lockedDuringCall.add(table);
            }
          }
          return accepting(request);
        });

    // On its own thread: a worker that called the PSP inside its transaction would also block
    // on its own row locks afterwards, and the test should fail rather than hang.
    ExecutorService runner = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> run = runner.submit(worker::runOnce);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> psp.requestsFor(op.pspRequestId()).size() == 1);
      assertThat(lockedDuringCall).isEmpty();
      assertThat(run.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      runner.shutdownNow();
    }
  }

  @Test
  void pspCallsDoNotStartAPlatformThreadEach() {
    psp.respond(PspOperationWorkerIntegrationTest::accepting);
    // A first full batch starts what is started once: the HTTP client's selector, the
    // virtual-thread carriers and connections in the pool.
    for (int i = 0; i < 5; i++) {
      newAuthorisation(100);
    }
    worker.runOnce();
    for (int i = 0; i < 5; i++) {
      newAuthorisation(100);
    }
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    long before = threads.getTotalStartedThreadCount();

    worker.runOnce();

    // Virtual threads are not counted here. Spring's fallback executor starts one per call: 6 for
    // this batch of 5 when measured, against 0 to 2 with the fix (a carrier or pool thread may
    // start meanwhile, depending on test order). Fewer than one per call is what matters.
    assertThat(threads.getTotalStartedThreadCount() - before).isLessThan(5);
  }

  // --- outcomes ----------------------------------------------------------------------------

  @Test
  void aFinalAnswerIsHandedToTheMoneyTransaction() {
    Created op = newAuthorisation(10_000);
    psp.respond(
        request -> Reply.of(201, StubPsp.operation(op.pspRequestId(), "psp_declined", "FAILED")));

    worker.runOnce();

    verify(outcomes)
        .apply(
            argThat(claimed -> claimed.id().equals(op.operationId())),
            argThat(
                outcome ->
                    !outcome.succeeded()
                        && outcome.pspReference().equals("psp_declined")
                        && "AMOUNT_MISMATCH".equals(outcome.failureReason())));
    // The mock applied nothing, so the operation is still PENDING and will be checked again.
    assertThat(row(op.operationId()).get("safety_net_scheduled")).isEqualTo(true);
  }

  @Test
  void theSafetyNetInquiresAndNeverResubmits() {
    Created op = newAuthorisation(10_000);
    psp.respond(PspOperationWorkerIntegrationTest::accepting);
    worker.runOnce();
    // The webhook never arrived and the safety-net time has come.
    jdbc.sql("UPDATE psp_operations SET next_attempt_at = now() WHERE id = ?")
        .param(op.operationId())
        .update();
    psp.respond(
        request ->
            Reply.of(
                200,
                StubPsp.operation(op.pspRequestId(), "psp_" + op.pspRequestId(), "SUCCEEDED")));

    worker.runOnce();

    List<StubPsp.Request> calls = psp.requestsFor(op.pspRequestId());
    assertThat(calls).hasSize(2);
    assertThat(calls.get(1).method()).isEqualTo("GET");
    verify(outcomes).apply(any(), argThat(PspResult.Final::succeeded));
  }

  // --- unknown outcomes --------------------------------------------------------------------

  @Test
  void aServerErrorReleasesTheLeaseAndRetriesAfterABackoff() {
    Created op = newAuthorisation(10_000);
    psp.respond(request -> Reply.of(503, "{\"code\": \"UNAVAILABLE\"}"));

    worker.runOnce();

    var row = row(op.operationId());
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("psp_reference")).isNull();
    assertThat((String) row.get("last_error")).contains("503");
    assertThat(row.get("leased")).isEqualTo(false);
    assertThat(row.get("needs_review")).isEqualTo(false);
    // First attempt: 1 s with equal jitter, so between 0.5 and 1 s.
    assertThat((Long) row.get("retry_in_ms")).isBetween(500L, 1000L);
    assertThat(psp.requestsFor(op.pspRequestId())).hasSize(1);
    verify(outcomes, never()).apply(any(), any());
  }

  void setAttempts(Created op, int attempts) {
    jdbc.sql("UPDATE psp_operations SET attempts = ? WHERE id = ?")
        .params(attempts, op.operationId())
        .update();
  }

  @Test
  void theBackoffDoublesWithEachAttemptUpToAMinute() {
    psp.respond(request -> Reply.of(502, ""));
    Created fourth = newAuthorisation(100);
    setAttempts(fourth, 3);
    Created ninth = newAuthorisation(200);
    setAttempts(ninth, 8);

    worker.runOnce();

    // 2^(4-1) = 8 s, jittered to 4..8 s; 2^(9-1) = 256 s capped at 60 s, jittered to 30..60 s.
    assertThat((Long) row(fourth.operationId()).get("retry_in_ms")).isBetween(4_000L, 8_000L);
    assertThat((Long) row(ninth.operationId()).get("retry_in_ms")).isBetween(30_000L, 60_000L);
  }

  @Test
  void afterTenAttemptsTheOperationNeedsReviewAndNothingElseChanges() {
    Created op = newAuthorisation(10_000);
    setAttempts(op, 9);
    psp.respond(request -> Reply.of(503, ""));

    worker.runOnce();

    var row = row(op.operationId());
    assertThat(row.get("attempts")).isEqualTo(10);
    assertThat(row.get("needs_review")).isEqualTo(true);
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat((String) row.get("last_error")).contains("503");
    assertThat(
            jdbc.sql("SELECT status FROM payments WHERE id = ?")
                .param(op.paymentId())
                .query(String.class)
                .single())
        .isEqualTo("AUTH_PENDING");
    // Set aside: never claimed again automatically, even when due.
    makeDue(op);
    assertThat(worker.runOnce()).isZero();
    assertThat(psp.requestsFor(op.pspRequestId())).hasSize(1);
  }

  @Test
  void aTimeoutIsUnknownNotAFailure() {
    Created op = newAuthorisation(10_000);
    psp.respond(
        request ->
            new Reply(
                201,
                StubPsp.operation(op.pspRequestId(), "psp_late", "SUCCEEDED"),
                Duration.ofSeconds(2)));

    worker.runOnce();

    var row = row(op.operationId());
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat((String) row.get("last_error")).startsWith("submit: I/O error");
    // Nothing resent the request behind the worker's back.
    assertThat(psp.requestsFor(op.pspRequestId())).hasSize(1);
    verify(outcomes, never()).apply(any(), any());
  }

  @Test
  void aRetryInquiresFirstAndDoesNotResubmitWhatThePspAlreadyHas() {
    Created op = newAuthorisation(10_000);
    // First attempt: the PSP records the operation, but the response is lost.
    psp.respond(
        request ->
            request.isSubmit()
                ? new Reply(
                    201,
                    StubPsp.operation(op.pspRequestId(), "psp_kept", "PENDING"),
                    Duration.ofSeconds(2))
                : Reply.of(200, StubPsp.operation(op.pspRequestId(), "psp_kept", "PENDING")));
    worker.runOnce();
    makeDue(op);

    worker.runOnce();

    List<StubPsp.Request> calls = psp.requestsFor(op.pspRequestId());
    assertThat(calls).extracting(StubPsp.Request::method).containsExactly("POST", "GET");
    var row = row(op.operationId());
    assertThat(row.get("psp_reference")).isEqualTo("psp_kept");
    assertThat(row.get("attempts")).isEqualTo(2);
    assertThat(row.get("last_error")).isNull();
  }

  @Test
  void aRetryResubmitsWithTheSameRequestIdOnlyIfThePspHasNoRecord() throws Exception {
    Created op = newAuthorisation(10_000);
    psp.respond(request -> Reply.of(502, ""));
    worker.runOnce();
    makeDue(op);
    psp.respond(PspOperationWorkerIntegrationTest::accepting);

    worker.runOnce();

    List<StubPsp.Request> calls = psp.requestsFor(op.pspRequestId());
    assertThat(calls).extracting(StubPsp.Request::method).containsExactly("POST", "GET", "POST");
    assertThat(body(calls.get(2)).path("psp_request_id").asText()).isEqualTo(op.pspRequestId());
    assertThat(row(op.operationId()).get("psp_reference")).isEqualTo("psp_" + op.pspRequestId());
  }

  // --- leases and concurrency --------------------------------------------------------------

  void expireLease(Created op) {
    jdbc.sql("UPDATE psp_operations SET lease_until = now() - interval '1 second' WHERE id = ?")
        .param(op.operationId())
        .update();
  }

  /** The retry is due now: no lease, and its backoff has elapsed. */
  void makeDue(Created op) {
    jdbc.sql(
            """
            UPDATE psp_operations SET lease_until = NULL, next_attempt_at = now() - interval '1 second'
             WHERE id = ?""")
        .param(op.operationId())
        .update();
  }

  @Test
  void drainWorksOffABacklogLargerThanOneBatch() {
    // batch-size is 5 in this test: 12 due operations take three claims, all in one drain.
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      ids.add(newAuthorisation(100).pspRequestId());
    }
    psp.respond(PspOperationWorkerIntegrationTest::accepting);

    assertThat(worker.drain()).isEqualTo(12);

    assertThat(ids).allSatisfy(id -> assertThat(psp.requestsFor(id)).hasSize(1));
    assertThat(worker.drain()).isZero();
  }

  @Test
  void anExpiredLeaseIsReclaimed() {
    Created op = newAuthorisation(10_000);
    // A worker claims the operation and dies before calling the PSP.
    assertThat(operations.claimDue(5, Duration.ofSeconds(30)))
        .extracting(ClaimedOperation::id)
        .containsExactly(op.operationId());
    psp.respond(PspOperationWorkerIntegrationTest::accepting);

    assertThat(worker.runOnce()).as("still leased").isZero();
    expireLease(op);
    assertThat(worker.runOnce()).isEqualTo(1);

    var row = row(op.operationId());
    assertThat(row.get("attempts")).isEqualTo(2);
    assertThat(row.get("psp_reference")).isEqualTo("psp_" + op.pspRequestId());
  }

  @Test
  void concurrentWorkersNeverProcessTheSameOperation() throws Exception {
    int count = 40;
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < count; i++) {
      ids.add(newAuthorisation(1_000 + i).pspRequestId());
    }
    Map<String, Integer> submits = new ConcurrentHashMap<>();
    psp.respond(
        request -> {
          Reply reply = accepting(request);
          if (request.isSubmit()) {
            ids.stream()
                .filter(request::mentions)
                .forEach(id -> submits.merge(id, 1, Integer::sum));
          }
          return new Reply(reply.status(), reply.body(), Duration.ofMillis(20));
        });

    int workers = 4;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
      for (int w = 0; w < workers; w++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  int claimed = 0;
                  for (int n; (n = worker.runOnce()) > 0; ) {
                    claimed += n;
                  }
                  return claimed;
                }));
      }
      start.countDown();
      int total = 0;
      for (var result : results) {
        total += result.get();
      }
      assertThat(total).isEqualTo(count);
    }

    assertThat(submits).hasSize(count);
    assertThat(submits.values()).containsOnly(1);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM psp_operations WHERE psp_request_id IN (:ids) AND attempts = 1")
                .param("ids", ids)
                .query(Long.class)
                .single())
        .isEqualTo(count);
  }

  @Test
  void concurrentClaimsAreDisjoint() throws Exception {
    for (int i = 0; i < 12; i++) {
      newAuthorisation(500);
    }
    CountDownLatch start = new CountDownLatch(1);
    List<Future<List<ClaimedOperation>>> claims = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
      for (int i = 0; i < 3; i++) {
        claims.add(
            pool.submit(
                () -> {
                  start.await();
                  return operations.claimDue(5, Duration.ofSeconds(30));
                }));
      }
      start.countDown();
      List<UUID> all = new ArrayList<>();
      for (var claim : claims) {
        all.addAll(claim.get().stream().map(ClaimedOperation::id).toList());
      }

      assertThat(all).hasSize(12).doesNotHaveDuplicates();
      assertThat(all.stream().collect(Collectors.toSet())).hasSize(12);
    }
  }
}
