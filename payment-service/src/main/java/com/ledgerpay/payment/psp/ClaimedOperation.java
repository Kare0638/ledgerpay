package com.ledgerpay.payment.psp;

import java.util.UUID;

/**
 * A due operation this worker holds the lease on, with what the PSP call needs.
 *
 * @param attempts including this one
 * @param failures consecutive earlier attempts that established nothing; reset once the PSP is
 *     reached
 * @param pspReference set once the PSP has accepted the operation
 * @param parentReference the PSP reference of the authorisation (capture, void) or capture
 *     (refund), or null if the parent has not succeeded locally yet
 */
public record ClaimedOperation(
    UUID id,
    UUID paymentId,
    PspOperationType type,
    String pspRequestId,
    String merchantId,
    long amountMinor,
    String currency,
    String pspReference,
    String parentReference,
    int attempts,
    int failures) {}
