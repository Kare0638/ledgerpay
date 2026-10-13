package com.ledgerpay.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param topic the payment events published by payment-service's outbox relay
 * @param deadLetterTopic where an event goes when it cannot be delivered or cannot be read
 * @param partitions the dead-letter topic's partitions when the service creates it
 * @param connectTimeout for a merchant's webhook endpoint
 * @param readTimeout for a merchant's webhook endpoint
 * @param retry how often and how far apart a failed delivery is tried again
 */
@ConfigurationProperties("ledgerpay.notification")
public record NotificationProperties(
    String topic,
    String deadLetterTopic,
    int partitions,
    Duration connectTimeout,
    Duration readTimeout,
    Retry retry) {

  /**
   * @param attempts retries after the first delivery attempt
   */
  public record Retry(
      int attempts, Duration initialInterval, double multiplier, Duration maxInterval) {}
}
