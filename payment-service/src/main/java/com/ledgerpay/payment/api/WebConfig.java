package com.ledgerpay.payment.api;

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

  /** Merchant API only; webhooks (HMAC), ops endpoints and actuator authenticate differently. */
  @Bean
  FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
      MerchantLookup merchants, ProblemWriter problems) {
    var registration = new FilterRegistrationBean<>(new ApiKeyAuthFilter(merchants, problems));
    registration.addUrlPatterns("/v1/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
