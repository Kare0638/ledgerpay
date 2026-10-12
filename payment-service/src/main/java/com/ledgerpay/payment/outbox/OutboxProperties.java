package com.ledgerpay.payment.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param topic where events are published, keyed by payment ID
 * @param partitions the topic's partitions when the service creates it
 * @param batchSize how many events one relay transaction claims
 * @param pollInterval how often the relay looks for unpublished events
 * @param publishTimeout how long the relay waits for the broker to acknowledge a batch; the claimed
 *     rows stay locked meanwhile
 * @param maxRejections how many times the broker may refuse an event for what it is before the
 *     event is quarantined
 */
@ConfigurationProperties("ledgerpay.outbox")
public record OutboxProperties(
    String topic,
    int partitions,
    int batchSize,
    Duration pollInterval,
    Duration publishTimeout,
    @DefaultValue("3") int maxRejections) {}
