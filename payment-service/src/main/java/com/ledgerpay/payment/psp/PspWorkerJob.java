package com.ledgerpay.payment.psp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the worker on a schedule. Tests turn it off and call {@link PspOperationWorker#runOnce}. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledgerpay.psp.worker.enabled", matchIfMissing = true)
class PspWorkerJob {

  private final PspOperationWorker worker;

  PspWorkerJob(PspOperationWorker worker) {
    this.worker = worker;
  }

  @Scheduled(fixedDelayString = "${ledgerpay.psp.poll-interval}")
  void run() {
    worker.runOnce();
  }
}
