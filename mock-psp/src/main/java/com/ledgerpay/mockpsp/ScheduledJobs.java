package com.ledgerpay.mockpsp;

import com.ledgerpay.mockpsp.operation.Settler;
import com.ledgerpay.mockpsp.webhook.WebhookDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Settles due operations and sends due webhooks. Tests can turn it off and drive both by hand. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "mockpsp.scheduling.enabled", matchIfMissing = true)
class ScheduledJobs {

  private final Settler settler;
  private final WebhookDispatcher dispatcher;
  private final int batchSize;

  ScheduledJobs(Settler settler, WebhookDispatcher dispatcher, MockPspProperties properties) {
    this.settler = settler;
    this.dispatcher = dispatcher;
    this.batchSize = properties.batchSize();
  }

  // Both jobs keep going while batches come back full, so the PSP is never what limits a load
  // test: one batch per poll capped it at batch-size per poll interval.

  @Scheduled(fixedDelayString = "${mockpsp.poll-interval}")
  void settle() {
    while (settler.settleDue() == batchSize) {}
  }

  @Scheduled(fixedDelayString = "${mockpsp.poll-interval}")
  void dispatch() {
    while (dispatcher.dispatchDue() == batchSize) {}
  }
}
