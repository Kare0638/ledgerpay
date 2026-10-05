package com.ledgerpay.payment.psp;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl the PSP API (mock-psp)
 * @param lease how long a claimed operation is hidden from other workers; also the delay before an
 *     operation whose call failed is tried again
 * @param batchSize how many operations one claim takes (design §9.1)
 * @param pollInterval how often the worker looks for due operations
 * @param safetyNetDelay when an accepted operation is inquired in case its webhook never arrives
 * @param provider the PSP's name: the inbox provider and the {@code psp_receivable} account
 * @param webhookSecret the HMAC key shared with the PSP for its webhooks
 * @param maxAttempts after this many consecutive attempts that established nothing, the operation
 *     is set aside for review instead of being retried; reaching the PSP starts the count again
 * @param maxBackoff the longest wait between two attempts
 */
@ConfigurationProperties("ledgerpay.psp")
public record PspProperties(
    URI baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    Duration lease,
    int batchSize,
    Duration pollInterval,
    Duration safetyNetDelay,
    String provider,
    String webhookSecret,
    int maxAttempts,
    Duration maxBackoff) {}
