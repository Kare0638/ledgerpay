package com.ledgerpay.payment.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the relay on a schedule. Tests turn it off and call it directly. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledgerpay.outbox.relay.enabled", matchIfMissing = true)
class OutboxRelayJob {

  private final OutboxRelay relay;

  OutboxRelayJob(OutboxRelay relay) {
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${ledgerpay.outbox.poll-interval}")
  void run() {
    relay.relayAll();
  }
}
