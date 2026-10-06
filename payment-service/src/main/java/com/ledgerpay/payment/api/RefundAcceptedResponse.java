package com.ledgerpay.payment.api;

import java.util.UUID;

/** 202 Accepted for a refund; stored for replay like {@link PaymentAcceptedResponse}. */
public record RefundAcceptedResponse(
    UUID refundId, UUID paymentId, String status, String statusUrl) {}
