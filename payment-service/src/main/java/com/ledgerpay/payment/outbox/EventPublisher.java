package com.ledgerpay.payment.outbox;

import java.util.concurrent.CompletableFuture;

/**
 * Where the outbox relay sends events (design §10). The future completes when the broker has
 * acknowledged the event, and only then is it marked published.
 */
public interface EventPublisher {

  CompletableFuture<Void> publish(OutboxEvent event);
}
