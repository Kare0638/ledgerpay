package com.ledgerpay.payment.reconciliation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param abandonedAfter a run still RUNNING this long after it started is taken to have died with
 *     its process, and is marked FAILED so that it does not look in progress for ever
 * @param sweepInterval how often abandoned runs are looked for
 */
@ConfigurationProperties("ledgerpay.recon")
public record ReconProperties(
    @DefaultValue("10m") Duration abandonedAfter, @DefaultValue("1m") Duration sweepInterval) {}
