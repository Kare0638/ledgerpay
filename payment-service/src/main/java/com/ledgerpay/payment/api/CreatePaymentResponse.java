package com.ledgerpay.payment.api;

import java.util.UUID;

/**
 * 202 Accepted: the money outcome is known only once the PSP confirms. This is what is stored for
 * replay; the current request's {@code traceId} is added to every response, first or replayed.
 */
public record CreatePaymentResponse(UUID paymentId, String status, String statusUrl) {}
