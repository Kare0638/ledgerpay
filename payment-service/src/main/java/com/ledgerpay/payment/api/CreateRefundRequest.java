package com.ledgerpay.payment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/payments/{id}/refunds} body. The currency is the payment's; partial refunds are
 * any amount up to what is still refundable.
 */
public record CreateRefundRequest(
    @NotBlank @Size(max = 128) String merchantReference,
    @NotNull @Min(1) @Max(100_000_000) Long amountMinor,
    @NotBlank @Size(max = 255) String reason) {}
