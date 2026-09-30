package com.ledgerpay.payment.idempotency;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The response to an idempotent request. {@code replayed} is true when it was read back from a
 * previous request with the same key instead of being produced now.
 */
public record IdempotentResponse(int status, JsonNode body, boolean replayed) {

  public static final String REPLAYED_HEADER = "Idempotent-Replayed";
}
