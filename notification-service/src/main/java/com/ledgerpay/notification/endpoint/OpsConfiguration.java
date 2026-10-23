package com.ledgerpay.notification.endpoint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpsConfiguration {

  @Bean
  FilterRegistrationBean<OpsKeyAuthFilter> opsKeyAuthFilter(
      @Value("${ledgerpay.ops.api-key-hash:}") String apiKeyHash) {
    var registration = new FilterRegistrationBean<>(new OpsKeyAuthFilter(apiKeyHash));
    // Metrics too, as in payment-service: Prometheus scrapes with the ops key as a bearer token.
    registration.addUrlPatterns("/ops/*", "/actuator/prometheus");
    return registration;
  }
}
