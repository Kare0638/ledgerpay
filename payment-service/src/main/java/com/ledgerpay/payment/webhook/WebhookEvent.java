package com.ledgerpay.payment.webhook;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** A PSP webhook body (design §9.2), in the PSP's snake_case. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WebhookEvent(
    @NotBlank String eventId,
    @NotBlank String eventType,
    @NotBlank String pspRequestId,
    @NotBlank String pspReference,
    @NotBlank String merchantId,
    @NotNull @Min(1) Long amountMinor,
    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
    // PENDING too: an event overtaken in delivery is acknowledged and recorded, never applied.
    @NotNull
        @Pattern(
            regexp = "SUCCEEDED|FAILED|PENDING",
            message = "must be SUCCEEDED, FAILED or PENDING")
        String status,
    @NotNull Instant occurredAt,
    @NotNull @Min(0) Integer resourceVersion) {

  /** The operation type, from {@code event_type} such as {@code capture.succeeded}. */
  String operationType() {
    int dot = eventType.indexOf('.');
    return (dot < 0 ? eventType : eventType.substring(0, dot)).toUpperCase();
  }
}
