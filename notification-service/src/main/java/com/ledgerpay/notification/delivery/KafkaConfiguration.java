package com.ledgerpay.notification.delivery;

import com.ledgerpay.notification.NotificationProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

  private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

  /** Declared here too, so the consumer can start before payment-service has created it. */
  @Bean
  NewTopic paymentEventsTopic(NotificationProperties properties) {
    return TopicBuilder.name(properties.topic()).partitions(properties.partitions()).build();
  }

  @Bean
  NewTopic paymentEventsDeadLetterTopic(NotificationProperties properties) {
    return TopicBuilder.name(properties.deadLetterTopic())
        .partitions(properties.partitions())
        .build();
  }

  /**
   * Design §10: a failed record is retried with exponential backoff, then published to the
   * dead-letter topic with the original key and value and the exception in headers. A malformed
   * event goes there at once. The retries block the partition, which is acceptable for three
   * retries of seconds; a production system would keep a delivery table with scheduled retries,
   * because merchant endpoints can be down for hours.
   *
   * <p>Our own database being unavailable is not the event's fault: three retries over seven
   * seconds would dead-letter every event consumed during a short outage, and nothing replays the
   * dead-letter topic. Those failures are retried every {@code max-interval} until the database is
   * back, holding the partition, which could not make progress without the database anyway.
   */
  @Bean
  DefaultErrorHandler errorHandler(
      KafkaTemplate<Object, Object> template, NotificationProperties properties) {
    String deadLetterTopic = properties.deadLetterTopic();
    // A negative partition lets the producer choose by key, so the topics need not match.
    var recoverer =
        new DeadLetterPublishingRecoverer(
            template,
            (record, exception) -> {
              log.error(
                  "Event at {}-{}@{} dead-lettered to {}",
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  deadLetterTopic,
                  exception);
              return new TopicPartition(deadLetterTopic, -1);
            });
    var retry = properties.retry();
    var backOff = new ExponentialBackOffWithMaxRetries(retry.attempts());
    backOff.setInitialInterval(retry.initialInterval().toMillis());
    backOff.setMultiplier(retry.multiplier());
    backOff.setMaxInterval(retry.maxInterval().toMillis());
    var handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(MalformedEventException.class);
    var untilTheDatabaseIsBack =
        new FixedBackOff(retry.maxInterval().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS);
    handler.setBackOffFunction(
        (record, exception) ->
            DatabaseFailures.isTemporary(exception) ? untilTheDatabaseIsBack : null);
    return handler;
  }
}
