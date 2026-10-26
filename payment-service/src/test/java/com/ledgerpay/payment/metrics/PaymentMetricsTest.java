package com.ledgerpay.payment.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PaymentMetricsTest {

  /**
   * An alert on increase() needs the series before the first event: created at 1 by the first
   * failed run after a start, it would never increase, and that run would go unnoticed.
   */
  @Test
  void reconFailureSeriesExistAtZeroBeforeAnyRun() {
    var meters = new SimpleMeterRegistry();

    new PaymentMetrics(meters);

    assertThat(meters.get("recon.runs.failed").counter().count()).isZero();
    for (String status : new String[] {"completed", "failed"}) {
      assertThat(meters.get("recon.run.duration").tag("status", status).timer().count())
          .as(status)
          .isZero();
    }
  }
}
