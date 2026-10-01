package com.ledgerpay.payment.psp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PspProperties.class)
class PspConfiguration {

  static final String CALL_EXECUTOR = "pspCallExecutor";

  /**
   * Runs the PSP calls of a claimed batch. The calls are I/O-bound, so a virtual thread each; the
   * M5 benchmark (#33) compares this with a platform thread pool and ADR 0008 records the choice.
   */
  @Bean(name = CALL_EXECUTOR, destroyMethod = "close")
  ExecutorService pspCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
