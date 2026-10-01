package com.ledgerpay.mockpsp.operation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/operations} body. {@code parentReference} is the {@code psp_reference} of the
 * authorisation (for capture and void) or the capture (for refund); an authorisation has none.
 */
public record SubmitRequest(
    @NotBlank @Size(max = 128) String pspRequestId,
    @NotBlank @Size(max = 128) String merchantId,
    @NotNull OperationType type,
    @Size(max = 128) String parentReference,
    @NotNull @Min(1) Long amountMinor,
    @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency) {}
