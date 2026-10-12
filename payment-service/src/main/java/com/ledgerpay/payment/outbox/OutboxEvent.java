package com.ledgerpay.payment.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * An outbox row on its way to the broker.
 *
 * @param id also the message's {@code eventId}, which consumers de-duplicate on
 * @param seq the relay order (V6)
 * @param aggregateId the payment ID, used as the message key
 * @param payload the event as JSON
 */
public record OutboxEvent(
    UUID id, long seq, UUID aggregateId, String eventType, String payload, Instant createdAt) {}
