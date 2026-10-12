package com.ledgerpay.payment.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox events (design §10). One transaction per batch: claim the events, publish them,
 * wait for the broker's acknowledgements, mark the acknowledged ones published, commit.
 *
 * <p>This is at-least-once. A crash after the broker acknowledged but before the commit leaves the
 * events unpublished, and the next run sends them again under the same {@code eventId}. An event
 * the broker did not acknowledge stays unpublished and is retried on the next run. Ledger
 * correctness never depends on this: if the broker is down, events wait in the table.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final Outbox outbox;
  private final EventPublisher publisher;
  private final TransactionTemplate transactions;
  private final int batchSize;
  private final Duration publishTimeout;

  public OutboxRelay(
      Outbox outbox,
      EventPublisher publisher,
      TransactionTemplate transactions,
      OutboxProperties properties) {
    this.outbox = outbox;
    this.publisher = publisher;
    this.transactions = transactions;
    this.batchSize = properties.batchSize();
    this.publishTimeout = properties.publishTimeout();
  }

  /**
   * Publishes batches until none is left or one is not fully acknowledged (the broker is probably
   * down: the next scheduled run tries again). Returns how many events were published.
   */
  public int relayAll() {
    int published = 0;
    while (true) {
      Batch batch = relayBatch();
      published += batch.published();
      if (batch.claimed() == 0 || batch.published() < batch.claimed()) {
        return published;
      }
    }
  }

  /** Claims, publishes and marks one batch. */
  public Batch relayBatch() {
    return transactions.execute(
        status -> {
          List<OutboxEvent> events = outbox.claimUnpublished(batchSize);
          List<UUID> acknowledged = publish(events);
          outbox.markPublished(acknowledged);
          return new Batch(events.size(), acknowledged.size());
        });
  }

  /** Sends the events and returns those the broker acknowledged within the timeout. */
  private List<UUID> publish(List<OutboxEvent> events) {
    List<CompletableFuture<Void>> sent = new ArrayList<>(events.size());
    for (OutboxEvent event : events) {
      CompletableFuture<Void> future = send(event);
      sent.add(future);
      if (future.isCompletedExceptionally()) {
        // Failed before reaching the broker, typically after waiting for metadata: the rest
        // would each wait as long, while this transaction holds their rows.
        break;
      }
    }
    long deadline = System.nanoTime() + publishTimeout.toNanos();
    List<UUID> acknowledged = new ArrayList<>(sent.size());
    for (int i = 0; i < sent.size(); i++) {
      OutboxEvent event = events.get(i);
      try {
        sent.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        acknowledged.add(event.id());
      } catch (ExecutionException | TimeoutException e) {
        log.warn(
            "Outbox event {} ({} for {}) not acknowledged; it stays unpublished",
            event.id(),
            event.eventType(),
            event.aggregateId(),
            e instanceof ExecutionException ? e.getCause() : e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (acknowledged.size() < events.size()) {
      log.warn(
          "Outbox relay: {} of {} claimed events published", acknowledged.size(), events.size());
    }
    return acknowledged;
  }

  private CompletableFuture<Void> send(OutboxEvent event) {
    try {
      return publisher.publish(event);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * @param claimed events taken by this batch
   * @param published events the broker acknowledged and that are now marked published
   */
  public record Batch(int claimed, int published) {}
}
