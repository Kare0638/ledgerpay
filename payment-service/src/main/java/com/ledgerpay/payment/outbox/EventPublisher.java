package com.ledgerpay.payment.outbox;

import java.util.concurrent.CompletableFuture;

/**
 * Where the outbox relay sends events (design §10). The future completes when the broker has
 * acknowledged the event, and only then is it marked published.
 */
public interface EventPublisher {

  CompletableFuture<Void> publish(OutboxEvent event);

  /**
   * Whether {@code failure} is about the event itself, so sending it again cannot succeed: it is
   * too large, say, or cannot be serialised. The relay goes on with the rest of the batch and
   * counts the refusal against the event. Any other failure is taken to be the broker's: the relay
   * stops the batch and tries everything again on its next run.
   */
  boolean rejectsEvent(Throwable failure);
}
