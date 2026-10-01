package com.ledgerpay.payment.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the inbox processor on a schedule. Tests turn it off and call it directly. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledgerpay.inbox.processor.enabled", matchIfMissing = true)
class InboxJob {

  private final InboxProcessor processor;

  InboxJob(InboxProcessor processor) {
    this.processor = processor;
  }

  @Scheduled(fixedDelayString = "${ledgerpay.inbox.poll-interval}")
  void run() {
    processor.processAll();
  }
}
