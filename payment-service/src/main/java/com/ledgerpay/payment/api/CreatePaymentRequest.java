package com.ledgerpay.payment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/payments} body. There is deliberately no merchant field: the merchant comes from
 * the API key, and any merchant ID in the body is ignored.
 */
public record CreatePaymentRequest(
    @NotBlank @Size(max = 128) String merchantReference,
    @NotNull @Min(1) @Max(100_000_000) Long amountMinor,
    @NotNull @Pattern(regexp = "GBP", message = "must be GBP") String currency) {}
