package com.ledgerpay.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ledgerpay.notification.NotificationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaConfigurationTest {

  @SuppressWarnings("unchecked")
  final DefaultErrorHandler handler =
      new KafkaConfiguration()
          .errorHandler(
              mock(KafkaTemplate.class),
              new NotificationProperties(
                  "payments.events",
                  "payments.events.dlq",
                  6,
                  Duration.ofSeconds(2),
                  Duration.ofSeconds(5),
                  new NotificationProperties.Retry(
                      3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))));

  /**
   * Retrying would read the same bytes the same way: a malformed event is dead-lettered without
   * holding up its partition. ({@code removeClassification} answers the earlier classification:
   * false means not retryable, null means the default, retryable.)
   */
  @Test
  void aMalformedEventIsNotRetried() {
    assertThat(handler.removeClassification(MalformedEventException.class)).isFalse();
  }

  @Test
  void aFailedDeliveryIsRetried() {
    assertThat(handler.removeClassification(DeliveryFailedException.class)).isNull();
  }
}
