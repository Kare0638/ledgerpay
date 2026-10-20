package com.ledgerpay.payment.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers the request filters on explicit URL patterns. */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

  @Bean
  FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
    var registration = new FilterRegistrationBean<>(new TraceIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /** Merchant API only; webhooks (HMAC), ops endpoints and metrics authenticate differently. */
  @Bean
  FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
      MerchantLookup merchants, ProblemWriter problems) {
    var registration = new FilterRegistrationBean<>(new ApiKeyAuthFilter(merchants, problems));
    registration.addUrlPatterns("/v1/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  /**
   * Operations endpoints, with their own key: a merchant key never opens them. Metrics too: they
   * show internal state such as the ledger alarm, so Prometheus scrapes with the ops key as a
   * bearer token. Health stays open for container probes.
   */
  @Bean
  FilterRegistrationBean<OpsKeyAuthFilter> opsKeyAuthFilter(
      @Value("${ledgerpay.ops.api-key-hash:}") String apiKeyHash, ProblemWriter problems) {
    var registration = new FilterRegistrationBean<>(new OpsKeyAuthFilter(apiKeyHash, problems));
    registration.addUrlPatterns("/ops/*", "/actuator/prometheus");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
