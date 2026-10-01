package com.ledgerpay.mockpsp.operation;

import com.ledgerpay.mockpsp.MockPspProperties;
import com.ledgerpay.mockpsp.webhook.WebhookEvents;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Brings accepted operations to their outcome. On the happy path every one succeeds. */
@Component
public class Settler {

  private final Operations operations;
  private final WebhookEvents webhooks;
  private final MockPspProperties properties;

  public Settler(Operations operations, WebhookEvents webhooks, MockPspProperties properties) {
    this.operations = operations;
    this.webhooks = webhooks;
    this.properties = properties;
  }

  /** Settles one batch of due operations; the outcome and its webhook commit together. */
  @Transactional
  public int settleDue() {
    var settled = operations.settleDue(properties.batchSize());
    settled.forEach(webhooks::enqueue);
    return settled.size();
  }
}
