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

  ScheduledJobs(Settler settler, WebhookDispatcher dispatcher) {
    this.settler = settler;
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${mockpsp.poll-interval}")
  void settle() {
    settler.settleDue();
  }

  @Scheduled(fixedDelayString = "${mockpsp.poll-interval}")
  void dispatch() {
    dispatcher.dispatchDue();
  }
}
