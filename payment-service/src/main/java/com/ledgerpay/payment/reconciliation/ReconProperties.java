package com.ledgerpay.payment.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param abandonedAfter a RUNNING run whose heartbeat is this old is taken to have died with its
 *     process, and is marked FAILED so that it does not look in progress for ever. A run renews its
 *     heartbeat every fifth of this while it executes, so a long run is never taken for dead
 * @param sweepInterval how often abandoned runs are looked for
 */
@ConfigurationProperties("ledgerpay.recon")
public record ReconProperties(
    @DefaultValue("10m") Duration abandonedAfter, @DefaultValue("1m") Duration sweepInterval) {

  /** How often a run in progress renews its heartbeat: well inside {@link #abandonedAfter}. */
  public Duration heartbeatInterval() {
    return abandonedAfter.dividedBy(5);
  }
}
