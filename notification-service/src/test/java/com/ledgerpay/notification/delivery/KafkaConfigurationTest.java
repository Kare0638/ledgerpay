package com.ledgerpay.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerpay.notification.NotificationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedRuntimeException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

class KafkaConfigurationTest {

  @SuppressWarnings("unchecked")
  final KafkaTemplate<Object, Object> deadLetters = mock(KafkaTemplate.class);

  final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  final DefaultErrorHandler handler =
      new KafkaConfiguration()
          .errorHandler(
              deadLetters,
              new NotificationProperties(
                  "payments.events",
                  "payments.events.dlq",
                  6,
                  Duration.ofSeconds(2),
                  Duration.ofSeconds(5),
                  new NotificationProperties.Retry(
                      3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))),
              meters);

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

  final ConsumerRecord<Object, Object> record =
      new ConsumerRecord<>("payments.events", 0, 0L, "payment", "event");

  @SuppressWarnings("unchecked")
  final Consumer<Object, Object> consumer = mock(Consumer.class);

  // Not running, so each backoff sleeps once briefly instead of its full interval.
  final MessageListenerContainer container = mock(MessageListenerContainer.class);

  /**
   * One failed attempt at the record, as the container reports what the listener threw. Returns
   * whether the handler keeps it in retry (RecordInRetryException, which is package-private) rather
   * than recovering it.
   */
  boolean fail(Exception thrownByTheListener) {
    try {
      handler.handleRemaining(
          new ListenerExecutionFailedException("listener failed", thrownByTheListener),
          List.of(record),
          consumer,
          container);
      return false;
    } catch (NestedRuntimeException e) {
      if (e.getClass().getSimpleName().equals("RecordInRetryException")) {
        return true;
      }
      throw e;
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void aFailedDeliveryIsDeadLetteredAfterThreeRetries() {
    when(deadLetters.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThat(fail(new DeliveryFailedException("Merchant m_1 answered 503"))).isTrue();
    }
    verify(deadLetters, never()).send(any(ProducerRecord.class));

    assertThat(meters.counter("notification.dead.lettered").count()).isZero();

    assertThat(fail(new DeliveryFailedException("Merchant m_1 answered 503"))).isFalse();
    verify(deadLetters).send(any(ProducerRecord.class));
    assertThat(meters.counter("notification.dead.lettered").count()).isEqualTo(1);
  }

  /**
   * Our own database down is not the event's fault: far past the three retries of a failed
   * delivery, the event is still being retried and nothing has been dead-lettered.
   */
  @Test
  @SuppressWarnings("unchecked")
  void aDatabaseOutageIsRetriedUntilItEndsAndNeverDeadLettered() {
    // The same failure each time: the handler starts counting afresh when the exception changes.
    for (int attempt = 0; attempt < 10; attempt++) {
      assertThat(fail(new CannotGetJdbcConnectionException("Connection refused"))).isTrue();
    }

    verify(deadLetters, never()).send(any(ProducerRecord.class));
    assertThat(meters.counter("notification.dead.lettered").count()).isZero();
  }
}
