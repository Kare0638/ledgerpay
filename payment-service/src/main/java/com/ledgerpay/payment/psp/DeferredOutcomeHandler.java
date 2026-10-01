package com.ledgerpay.payment.psp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in until the money transaction exists (#8): changes nothing, so the operation stays PENDING
 * and its safety-net inquiry brings the outcome back later. No outcome is lost, only deferred.
 */
@Component
class DeferredOutcomeHandler implements PspOutcomeHandler {

  private static final Logger log = LoggerFactory.getLogger(DeferredOutcomeHandler.class);

  @Override
  public void apply(ClaimedOperation operation, PspResult.Final outcome) {
    log.info(
        "PSP outcome for {} ({}) deferred until the money transaction exists: {}",
        operation.pspRequestId(),
        operation.type(),
        outcome.succeeded() ? "SUCCEEDED" : "FAILED " + outcome.failureReason());
  }
}
