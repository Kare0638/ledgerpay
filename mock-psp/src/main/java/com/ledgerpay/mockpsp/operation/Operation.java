package com.ledgerpay.mockpsp.operation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ledgerpay.mockpsp.fault.Fault;
import java.time.Instant;
import java.util.Objects;

/**
 * An operation as mock-psp records it; also the submit and inquiry response body, which never shows
 * the injected fault.
 */
public record Operation(
    String pspReference,
    String pspRequestId,
    String merchantId,
    OperationType type,
    String parentReference,
    long amountMinor,
    String currency,
    OperationStatus status,
    FailureReason failureReason,
    int resourceVersion,
    Instant createdAt,
    Instant succeededAt,
    Instant updatedAt,
    @JsonIgnore Fault fault) {

  /** True if {@code request} asks for exactly this operation, so a repeat can be answered. */
  boolean sameParameters(SubmitRequest request) {
    return merchantId.equals(request.merchantId())
        && type == request.type()
        && Objects.equals(parentReference, request.parentReference())
        && amountMinor == request.amountMinor()
        && currency.equals(request.currency());
  }
}
