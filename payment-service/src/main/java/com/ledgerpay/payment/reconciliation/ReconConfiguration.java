package com.ledgerpay.payment.reconciliation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReconProperties.class)
class ReconConfiguration {

  /** Runs in the background, a virtual thread each; closing waits for the ones in progress. */
  @Bean(destroyMethod = "close")
  ExecutorService reconExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /** Renews the heartbeats of the runs in progress; one daemon thread, a few updates a minute. */
  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService reconHeartbeat() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("recon-heartbeat").daemon().factory());
  }
}
