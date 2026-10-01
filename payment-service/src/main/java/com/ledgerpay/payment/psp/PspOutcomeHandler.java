package com.ledgerpay.payment.psp;

/**
 * Applies a PSP's final answer: the money transaction of design §9.3, shared by webhooks and
 * inquiries. It runs in its own transaction, after the PSP call has returned.
 */
public interface PspOutcomeHandler {

  void apply(ClaimedOperation operation, PspResult.Final outcome);
}
