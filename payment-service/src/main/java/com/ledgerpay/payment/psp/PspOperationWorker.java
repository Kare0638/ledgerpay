package com.ledgerpay.payment.psp;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Drives every PSP call from persisted operations (design §9.1). Claiming is one short statement;
 * the calls run afterwards, outside any transaction, so no money row is locked while the PSP is
 * slow. A crashed worker only delays its operations until their leases expire.
 */
@Component
public class PspOperationWorker {

  private static final Logger log = LoggerFactory.getLogger(PspOperationWorker.class);

  private final PspOperations operations;
  private final PspClient psp;
  private final PspOutcomeHandler outcomes;
  private final PspProperties properties;
  private final ExecutorService calls;

  public PspOperationWorker(
      PspOperations operations,
      PspClient psp,
      PspOutcomeHandler outcomes,
      PspProperties properties,
      @Qualifier(PspConfiguration.CALL_EXECUTOR) ExecutorService calls) {
    this.operations = operations;
    this.psp = psp;
    this.outcomes = outcomes;
    this.properties = properties;
    this.calls = calls;
  }

  /** Claims one batch, makes its PSP calls concurrently and returns how many were claimed. */
  public int runOnce() {
    List<ClaimedOperation> claimed =
        operations.claimDue(properties.batchSize(), properties.lease());
    List<Future<?>> running =
        claimed.stream().<Future<?>>map(op -> calls.submit(() -> process(op))).toList();
    for (int i = 0; i < running.size(); i++) {
      try {
        running.get(i).get();
      } catch (ExecutionException e) {
        // The lease is still held, so the operation comes back once it expires.
        log.error("PSP operation {} failed", claimed.get(i).pspRequestId(), e.getCause());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return claimed.size();
      }
    }
    return claimed.size();
  }

  /**
   * Claims batches until one comes back less than full, so a backlog is worked off at once. One
   * batch per poll capped the worker at batch-size per poll interval (40 operations a second by
   * default), which the load test (#34) hit at about 17 payments a second while the CPU was idle.
   */
  public int drain() {
    int total = 0;
    int claimed;
    do {
      claimed = runOnce();
      total += claimed;
    } while (claimed == properties.batchSize() && !Thread.currentThread().isInterrupted());
    return total;
  }

  void process(ClaimedOperation operation) {
    switch (call(operation)) {
      case PspResult.Accepted accepted ->
          operations.markAccepted(
              operation.id(), accepted.pspReference(), properties.safetyNetDelay());
      case PspResult.Final outcome -> {
        outcomes.apply(operation, outcome);
        // Still PENDING only if the outcome could not be applied yet: check again later.
        operations.markAccepted(
            operation.id(), outcome.pspReference(), properties.safetyNetDelay());
      }
      case PspResult.Unknown unknown -> {
        boolean exhausted = operation.attempts() >= properties.maxAttempts();
        Duration retryIn =
            RetryBackoff.delay(
                operation.attempts(), properties.maxBackoff(), ThreadLocalRandom.current());
        operations.recordFailure(operation.id(), unknown.error(), retryIn, exhausted);
        if (exhausted) {
          // Alert: a human decides. Nothing is released or rolled back, as the outcome is unknown.
          log.error(
              "PSP operation {} needs review after {} attempts: {}",
              operation.pspRequestId(),
              operation.attempts(),
              unknown.error());
        } else {
          log.warn(
              "PSP call for {} attempt {} failed, retrying in {} ms: {}",
              operation.pspRequestId(),
              operation.attempts(),
              retryIn.toMillis(),
              unknown.error());
        }
      }
      case PspResult.NotFound notFound ->
          throw new IllegalStateException("call() never returns NotFound");
    }
  }

  /**
   * Submits only on the first attempt. Any later attempt — a retry after a timeout, or the
   * safety-net check of an accepted operation — inquires first, because an earlier submit may have
   * reached the PSP; resubmitting with the same request ID is safe only if it has no record.
   */
  private PspResult call(ClaimedOperation operation) {
    if (operation.type() != PspOperationType.AUTHORIZE && operation.parentReference() == null) {
      return new PspResult.Unknown(
          "Parent of " + operation.type() + " has no successful PSP operation yet");
    }
    if (operation.pspReference() == null && operation.attempts() == 1) {
      return psp.submit(operation);
    }
    PspResult found = psp.inquire(operation.pspRequestId());
    if (!(found instanceof PspResult.NotFound)) {
      return found;
    }
    if (operation.pspReference() != null) {
      return new PspResult.Unknown(
          "PSP has no record of accepted operation " + operation.pspReference());
    }
    return psp.submit(operation);
  }
}
