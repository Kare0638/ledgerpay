package com.ledgerpay.payment.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfiguration {

  /** Created at startup when the broker is reachable (and {@code spring.kafka.admin} allows it). */
  @Bean
  NewTopic paymentEventsTopic(OutboxProperties properties) {
    return TopicBuilder.name(properties.topic()).partitions(properties.partitions()).build();
  }
}
