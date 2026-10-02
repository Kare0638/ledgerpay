package com.ledgerpay.mockpsp;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param settleDelay how long an accepted operation stays PENDING before it succeeds
 * @param pollInterval how often due operations are settled and due webhooks sent
 * @param batchSize how many operations or webhooks one poll handles
 */
@ConfigurationProperties("mockpsp")
public record MockPspProperties(
    Duration settleDelay, Duration pollInterval, int batchSize, Webhook webhook, Faults faults) {

  /**
   * @param responseDelay how long {@code TIMEOUT_AFTER_COMMIT} holds the submit response; longer
   *     than the caller's read timeout
   * @param webhookDelay how late {@code DELAY_WEBHOOK} sends the outcome
   * @param staleEventDelay how long after the outcome {@code OUT_OF_ORDER} sends the older event
   */
  public record Faults(Duration responseDelay, Duration webhookDelay, Duration staleEventDelay) {}

  /**
   * @param url where every webhook is sent (payment-service's {@code /webhooks/psp})
   * @param secret the HMAC key shared with the receiver
   * @param lease how long a claimed delivery is hidden from other pollers
   * @param maxAttempts after this many failed deliveries mock-psp gives up; the receiver's
   *     safety-net inquiry still finds the outcome
   */
  public record Webhook(
      URI url,
      String secret,
      Duration connectTimeout,
      Duration readTimeout,
      Duration lease,
      int maxAttempts) {}
}
