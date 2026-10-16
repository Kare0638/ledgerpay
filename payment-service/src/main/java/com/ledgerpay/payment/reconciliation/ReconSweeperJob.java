package com.ledgerpay.payment.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A run whose process died stays RUNNING; this marks it FAILED once it is older than {@code
 * ledgerpay.recon.abandoned-after}, so that nobody waits on it. Its report can simply be run again.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledgerpay.recon.sweeper.enabled", matchIfMissing = true)
class ReconSweeperJob {

  private static final Logger log = LoggerFactory.getLogger(ReconSweeperJob.class);

  private final ReconRuns runs;
  private final ReconProperties properties;

  ReconSweeperJob(ReconRuns runs, ReconProperties properties) {
    this.runs = runs;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${ledgerpay.recon.sweep-interval:1m}")
  void sweep() {
    int failed = runs.failAbandoned(properties.abandonedAfter());
    if (failed > 0) {
      log.warn("Marked {} abandoned recon run(s) FAILED", failed);
    }
  }
}
