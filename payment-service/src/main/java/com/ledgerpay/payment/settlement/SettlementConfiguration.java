package com.ledgerpay.payment.settlement;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SettlementProperties.class)
class SettlementConfiguration {

  @Bean
  SettlementCsvParser settlementCsvParser(SettlementProperties properties) {
    return new SettlementCsvParser(properties.maxRows(), properties.maxStoredErrors());
  }
}
