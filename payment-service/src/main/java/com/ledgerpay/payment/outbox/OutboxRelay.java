package com.ledgerpay.payment.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>An event the broker refuses for what it is, too large say, is only that event's problem: the
 * rest of the batch goes on, and after {@code max-rejections} refusals the event is quarantined so
 * that it stops taking a place in every batch. Its payment's later events wait behind it.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final Outbox outbox;
  private final EventPublisher publisher;
  private final TransactionTemplate transactions;
  private final int batchSize;
  private final Duration publishTimeout;
  private final int maxRejections;

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
    this.maxRejections = properties.maxRejections();
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
          Sent sent = publish(events);
          outbox.markPublished(sent.acknowledged());
          sent.rejected().forEach(this::recordRejection);
          return new Batch(events.size(), sent.acknowledged().size());
        });
  }

  private void recordRejection(OutboxEvent event, Throwable failure) {
    if (outbox.recordRejection(event.id(), describe(failure), maxRejections)) {
      // Alert: a human decides. The payment's later events wait behind this one.
      log.error(
          "Outbox event {} ({} for {}) quarantined after {} rejections",
          event.id(),
          event.eventType(),
          event.aggregateId(),
          maxRejections,
          failure);
    } else {
      log.warn(
          "Outbox event {} ({} for {}) rejected by the broker; it stays unpublished",
          event.id(),
          event.eventType(),
          event.aggregateId(),
          failure);
    }
  }

  /** The failure and its causes, so the client's wrapper does not hide the broker's reason. */
  static String describe(Throwable failure) {
    StringBuilder description = new StringBuilder(failure.toString());
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      description.append("; caused by ").append(cause);
    }
    return description.toString();
  }

  /**
   * @param acknowledged events the broker acknowledged within the timeout
   * @param rejected events the broker refused for what they are, with the failure
   */
  private record Sent(List<UUID> acknowledged, Map<OutboxEvent, Throwable> rejected) {}

  /** Sends the events and sorts out which the broker acknowledged and which it refused. */
  private Sent publish(List<OutboxEvent> events) {
    List<CompletableFuture<Void>> sent = new ArrayList<>(events.size());
    for (OutboxEvent event : events) {
      CompletableFuture<Void> future = send(event);
      sent.add(future);
      if (future.isCompletedExceptionally()
          && !publisher.rejectsEvent(future.handle((result, failure) -> failure).join())) {
        // Failed before reaching the broker, typically after waiting for metadata: the rest
        // would each wait as long, while this transaction holds their rows. A refusal of this
        // one event, by contrast, says nothing about the others.
        break;
      }
    }
    long deadline = System.nanoTime() + publishTimeout.toNanos();
    List<UUID> acknowledged = new ArrayList<>(sent.size());
    Map<OutboxEvent, Throwable> rejected = new LinkedHashMap<>();
    for (int i = 0; i < sent.size(); i++) {
      OutboxEvent event = events.get(i);
      try {
        sent.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        acknowledged.add(event.id());
      } catch (ExecutionException e) {
        if (publisher.rejectsEvent(e.getCause())) {
          rejected.put(event, e.getCause());
        } else {
          logUnacknowledged(event, e.getCause());
        }
      } catch (TimeoutException e) {
        logUnacknowledged(event, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (acknowledged.size() < events.size()) {
      log.warn(
          "Outbox relay: {} of {} claimed events published", acknowledged.size(), events.size());
    }
    return new Sent(acknowledged, rejected);
  }

  private static void logUnacknowledged(OutboxEvent event, Throwable failure) {
    log.warn(
        "Outbox event {} ({} for {}) not acknowledged; it stays unpublished",
        event.id(),
        event.eventType(),
        event.aggregateId(),
        failure);
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
