package com.ledgerpay.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.payment.PaymentService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The relay against PostgreSQL with a recording publisher in place of Kafka: what is marked
 * published, what is sent again, and the per-payment order under concurrent relays.
 * KafkaOutboxRelayIntegrationTest covers the real broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfiguration.class)
class OutboxRelayIntegrationTest {

  @Autowired Outbox outbox;
  @Autowired PaymentService payments;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired TransactionTemplate tx;

  final RecordingPublisher publisher = new RecordingPublisher();

  /** Events left by other tests in this database are not this test's to publish. */
  @BeforeEach
  void onlyThisTestsEvents() {
    jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL").update();
  }

  OutboxRelay relay(Outbox outbox, int batchSize) {
    return new OutboxRelay(
        outbox,
        publisher,
        tx,
        new OutboxProperties(
            "payments.events", 1, batchSize, Duration.ZERO, Duration.ofSeconds(5)));
  }

  OutboxRelay relay() {
    return relay(outbox, 100);
  }

  UUID append(UUID aggregate, String type) {
    return tx.execute(status -> outbox.append(aggregate, type, Map.of("n", type)));
  }

  boolean published(UUID eventId) {
    return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?")
        .param(eventId)
        .query(Boolean.class)
        .single();
  }

  @Test
  void aRolledBackTransactionLeavesNoOutboxRow() {
    String merchant = "m_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, "hash_" + merchant)
        .update();
    String reference = "ref_" + UUID.randomUUID();
    UUID paymentId =
        tx.execute(
            status -> {
              UUID id = payments.create(merchant, reference, Money.of(1_000, Money.GBP)).id();
              status.setRollbackOnly();
              return id;
            });

    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?")
                .param(paymentId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(relay().relayAll()).isZero();
    assertThat(publisher.sent).isEmpty();
  }

  @Test
  void publishesEachEventOnceInOrderAndMarksItPublished() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID a1 = append(first, "PaymentCreated");
    UUID b1 = append(second, "PaymentCreated");
    UUID a2 = append(first, "PaymentAuthorized");
    UUID a3 = append(first, "PaymentCaptured");

    assertThat(relay().relayAll()).isEqualTo(4);
    assertThat(relay().relayAll()).isZero();

    assertThat(publisher.ids()).containsExactly(a1, b1, a2, a3);
    assertThat(List.of(a1, a2, a3, b1)).allMatch(this::published);
  }

  @Test
  void aPaymentsNextEventWaitsUntilTheEarlierOneIsPublished() {
    UUID payment = UUID.randomUUID();
    UUID created = append(payment, "PaymentCreated");
    UUID authorized = append(payment, "PaymentAuthorized");

    OutboxRelay.Batch batch = relay().relayBatch();

    assertThat(batch).isEqualTo(new OutboxRelay.Batch(1, 1));
    assertThat(publisher.ids()).containsExactly(created);
    assertThat(published(authorized)).isFalse();
  }

  @Test
  void anUnacknowledgedEventStaysUnpublishedAndHoldsBackItsPaymentUntilTheNextRun() {
    UUID payment = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID created = append(payment, "PaymentCreated");
    UUID authorized = append(payment, "PaymentAuthorized");
    UUID otherCreated = append(other, "PaymentCreated");
    publisher.failWhen(event -> event.id().equals(created));

    assertThat(relay().relayAll()).isEqualTo(1);

    assertThat(publisher.ids()).containsExactly(otherCreated);
    assertThat(published(created)).isFalse();
    assertThat(published(authorized)).isFalse();

    publisher.failWhen(event -> false);
    assertThat(relay().relayAll()).isEqualTo(2);
    assertThat(publisher.ids()).containsExactly(otherCreated, created, authorized);
  }

  @Test
  void aSendThatFailsAtOnceStopsTheBatch() {
    for (int i = 0; i < 5; i++) {
      append(UUID.randomUUID(), "PaymentCreated");
    }
    publisher.throwWhen(event -> true);

    OutboxRelay.Batch batch = relay().relayBatch();

    assertThat(batch).isEqualTo(new OutboxRelay.Batch(5, 0));
    assertThat(publisher.attempts).hasValue(1);
  }

  /**
   * The relay dies after the broker acknowledged but before its transaction commits. The event is
   * still unpublished, and the next run sends it again with the same eventId: at-least-once.
   */
  @Test
  void aCrashBeforeTheCommitSendsTheEventAgainWithTheSameId() {
    UUID event = append(UUID.randomUUID(), "PaymentCaptured");
    Outbox crashing =
        new Outbox(jdbc, json) {
          @Override
          public void markPublished(Collection<UUID> ids) {
            super.markPublished(ids);
            throw new IllegalStateException("relay killed before commit");
          }
        };

    assertThatThrownBy(() -> relay(crashing, 100).relayAll())
        .hasMessageContaining("killed before commit");
    assertThat(published(event)).isFalse();

    assertThat(relay().relayAll()).isEqualTo(1);
    assertThat(publisher.ids()).containsExactly(event, event);
    assertThat(published(event)).isTrue();
  }

  /**
   * Four relays at once over 8 payments with 25 events each, written round-robin so one payment's
   * events are 8 apart: closer than a batch, so relays without the earliest-event rule would take
   * one payment's events at the same time. Every event is published exactly once (no failures, so
   * no re-sends), and each payment's events in the order they were written.
   */
  @Test
  void concurrentRelaysPublishEachEventOnceAndKeepEachPaymentsOrder() throws Exception {
    Map<UUID, List<UUID>> written = new ConcurrentHashMap<>();
    List<UUID> aggregates = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      aggregates.add(UUID.randomUUID());
    }
    for (int n = 0; n < 25; n++) {
      for (UUID aggregate : aggregates) {
        written.computeIfAbsent(aggregate, a -> new ArrayList<>()).add(append(aggregate, "E" + n));
      }
    }
    publisher.delay = Duration.ofMillis(2);

    int relays = 4;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(relays)) {
      for (int i = 0; i < relays; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  // Small batches so the relays interleave; a relay stops when it finds nothing,
                  // which may be while another holds the rest, so each keeps going until all are.
                  OutboxRelay relay = relay(outbox, 7);
                  int published = 0;
                  while (unpublished() > 0) {
                    published += relay.relayAll();
                  }
                  return published;
                }));
      }
      start.countDown();
      int total = 0;
      for (Future<Integer> result : results) {
        total += result.get();
      }
      assertThat(total).isEqualTo(200);
    }

    assertThat(publisher.sent).hasSize(200);
    assertThat(publisher.ids()).doesNotHaveDuplicates();
    Map<UUID, List<UUID>> sentByAggregate =
        publisher.sent.stream()
            .collect(
                Collectors.groupingBy(
                    OutboxEvent::aggregateId,
                    Collectors.mapping(OutboxEvent::id, Collectors.toList())));
    assertThat(sentByAggregate).isEqualTo(written);
  }

  long unpublished() {
    return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
        .query(Long.class)
        .single();
  }

  /** Records what it is asked to send; can fail selected events, either way a broker can. */
  static class RecordingPublisher implements EventPublisher {

    final List<OutboxEvent> sent = new CopyOnWriteArrayList<>();
    final AtomicInteger attempts = new AtomicInteger();
    volatile Predicate<OutboxEvent> nack = event -> false;
    volatile Predicate<OutboxEvent> thrown = event -> false;
    volatile Duration delay = Duration.ZERO;

    void failWhen(Predicate<OutboxEvent> predicate) {
      nack = predicate;
    }

    void throwWhen(Predicate<OutboxEvent> predicate) {
      thrown = predicate;
    }

    List<UUID> ids() {
      return sent.stream().map(OutboxEvent::id).toList();
    }

    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
      attempts.incrementAndGet();
      if (thrown.test(event)) {
        throw new IllegalStateException("no metadata for the topic");
      }
      if (nack.test(event)) {
        // A broker refuses after the send returns, as Kafka does; a future that has already
        // failed is what the relay takes for a send that never left.
        var refused = new CompletableFuture<Void>();
        CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS)
            .execute(() -> refused.completeExceptionally(new IllegalStateException("refused")));
        return refused;
      }
      if (!delay.isZero()) {
        try {
          Thread.sleep(delay);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      sent.add(event);
      return CompletableFuture.completedFuture(null);
    }
  }
}
