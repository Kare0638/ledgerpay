package com.ledgerpay.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.webhook.WebhookSignature;
import com.ledgerpay.notification.KafkaTestcontainersConfiguration;
import com.ledgerpay.notification.PostgresTestcontainersConfiguration;
import com.ledgerpay.notification.endpoint.MerchantEndpoints;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.kafka.KafkaContainer;

/**
 * notification-service against Kafka and PostgreSQL in Testcontainers, with merchants as a stub
 * HTTP server. Retries are shortened to 100 ms so the dead-letter path runs in about a second.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "spring.kafka.admin.auto-create=true",
      "ledgerpay.notification.retry.initial-interval=100ms",
      "ledgerpay.notification.retry.max-interval=400ms"
    })
@Import({PostgresTestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class NotificationDeliveryIntegrationTest {

  static final String TOPIC = "payments.events";
  static final String DLQ = "payments.events.dlq";
  static StubMerchant merchants;

  @Autowired KafkaTemplate<Object, Object> kafka;
  @Autowired KafkaContainer broker;
  @Autowired MerchantEndpoints endpoints;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;

  @BeforeAll
  static void startMerchants() {
    merchants = new StubMerchant();
  }

  @AfterAll
  static void stopMerchants() {
    merchants.stop();
  }

  /** A merchant with a registered endpoint; returns its signing secret. */
  String registered(String merchant) {
    return endpoints.register(merchant, URI.create(merchants.url(merchant))).signingSecret();
  }

  /** An envelope as payment-service's relay publishes it (ADR 0006). */
  String envelope(UUID eventId, UUID paymentId, String merchant) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paymentId", paymentId);
    data.put("merchantId", merchant);
    data.put("status", "CAPTURED");
    data.put("amountMinor", 10_000);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId);
    envelope.put("eventType", "PaymentCaptured");
    envelope.put("paymentId", paymentId);
    envelope.put("occurredAt", "2026-10-12T09:00:00Z");
    envelope.put("data", data);
    try {
      return json.writeValueAsString(envelope);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  RecordMetadata publish(UUID paymentId, String value) throws Exception {
    return kafka.send(TOPIC, paymentId.toString(), value).get().getRecordMetadata();
  }

  long processed(UUID eventId) {
    return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = ?")
        .param(eventId)
        .query(Long.class)
        .single();
  }

  Map<String, Object> delivery(UUID eventId) {
    return jdbc.sql("SELECT * FROM webhook_deliveries WHERE event_id = ?")
        .param(eventId)
        .query()
        .singleRow();
  }

  /** The dead-letter records keyed by the payment, reading for up to {@code wait}. */
  List<ConsumerRecord<String, String>> deadLetters(UUID paymentId, Duration wait) {
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
      var partitions =
          consumer.partitionsFor(DLQ).stream()
              .map(info -> new TopicPartition(DLQ, info.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      List<ConsumerRecord<String, String>> found = new ArrayList<>();
      Instant deadline = Instant.now().plus(wait);
      while (Instant.now().isBefore(deadline)) {
        consumer
            .poll(Duration.ofMillis(200))
            .forEach(
                record -> {
                  if (paymentId.toString().equals(record.key())) {
                    found.add(record);
                  }
                });
        if (!found.isEmpty()) {
          break;
        }
      }
      return found;
    }
  }

  static String header(ConsumerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  long committedOffset(TopicPartition partition) throws Exception {
    try (var admin =
        AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()))) {
      var offsets =
          admin
              .listConsumerGroupOffsets("notification-service")
              .partitionsToOffsetAndMetadata()
              .get();
      var offset = offsets.get(partition);
      return offset == null ? -1 : offset.offset();
    }
  }

  @Test
  void deliversASignedWebhookRecordsItAndCommitsTheOffset() throws Exception {
    String merchant = "m_" + UUID.randomUUID();
    String secret = registered(merchant);
    UUID eventId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    String value = envelope(eventId, paymentId, merchant);

    RecordMetadata sent = publish(paymentId, value);

    await().atMost(Duration.ofSeconds(30)).until(() -> processed(eventId) == 1);
    var request = merchants.requests(merchant).getFirst();
    assertThat(request.body()).isEqualTo(value);
    assertThat(request.headers())
        .containsEntry("x-ledgerpay-event-id", eventId.toString())
        .containsEntry("x-ledgerpay-event-type", "PaymentCaptured");
    assertThat(
            WebhookSignature.verify(
                secret.getBytes(StandardCharsets.UTF_8),
                request.headers().get("x-ledgerpay-timestamp"),
                request.body().getBytes(StandardCharsets.UTF_8),
                request.headers().get("x-ledgerpay-signature"),
                Instant.now()))
        .isTrue();
    assertThat(delivery(eventId))
        .containsEntry("status", "DELIVERED")
        .containsEntry("merchant_id", merchant)
        .containsEntry("payment_id", paymentId)
        .containsEntry("response_status", 200);
    var partition = new TopicPartition(TOPIC, sent.partition());
    await().atMost(Duration.ofSeconds(10)).until(() -> committedOffset(partition) > sent.offset());
  }

  /** Done when for #15: the outbox relay re-sent an event, and the merchant gets it once. */
  @Test
  void aDuplicateEventIsDeliveredOnce() throws Exception {
    String merchant = "m_" + UUID.randomUUID();
    registered(merchant);
    UUID eventId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    String value = envelope(eventId, paymentId, merchant);
    UUID marker = UUID.randomUUID();

    publish(paymentId, value);
    publish(paymentId, value);
    // Same key, so the same partition and in order: once this is processed, so are both copies.
    publish(paymentId, envelope(marker, paymentId, merchant));

    await().atMost(Duration.ofSeconds(30)).until(() -> processed(marker) == 1);
    assertThat(merchants.requests(merchant))
        .extracting(request -> request.headers().get("x-ledgerpay-event-id"))
        .containsExactly(eventId.toString(), marker.toString());
    assertThat(processed(eventId)).isEqualTo(1);
  }

  @Test
  void aMerchantThatRecoversWithinTheRetriesGetsTheWebhookOnce() throws Exception {
    String merchant = "m_" + UUID.randomUUID();
    registered(merchant);
    merchants.answer(merchant, 503, 500);
    UUID eventId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();

    publish(paymentId, envelope(eventId, paymentId, merchant));

    await().atMost(Duration.ofSeconds(30)).until(() -> processed(eventId) == 1);
    assertThat(merchants.requests(merchant)).hasSize(3);
    assertThat(delivery(eventId)).containsEntry("status", "DELIVERED");
    assertThat(deadLetters(paymentId, Duration.ofSeconds(1))).isEmpty();
  }

  /** Done when for #15: a permanently failing message lands in the DLQ after three retries. */
  @Test
  void aMerchantThatKeepsFailingSendsTheEventToTheDeadLetterTopic() throws Exception {
    String merchant = "m_" + UUID.randomUUID();
    registered(merchant);
    merchants.answerAlways(merchant, 500);
    UUID eventId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    String value = envelope(eventId, paymentId, merchant);

    publish(paymentId, value);

    var deadLetters = deadLetters(paymentId, Duration.ofSeconds(30));
    assertThat(deadLetters).hasSize(1);
    var deadLetter = deadLetters.getFirst();
    assertThat(deadLetter.value()).isEqualTo(value);
    assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
    assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
        .contains("ListenerExecutionFailedException");
    assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
        .isEqualTo(DeliveryFailedException.class.getName());
    assertThat(merchants.requests(merchant)).hasSize(4); // the attempt and three retries
    assertThat(processed(eventId)).isZero();
  }

  @Test
  void aMalformedEventIsDeadLetteredAtOnceAndTheNextOneStillGoesOut() throws Exception {
    String merchant = "m_" + UUID.randomUUID();
    registered(merchant);
    UUID paymentId = UUID.randomUUID();
    UUID next = UUID.randomUUID();

    publish(paymentId, "{\"eventType\":\"PaymentCaptured\"}");
    publish(paymentId, envelope(next, paymentId, merchant));

    await().atMost(Duration.ofSeconds(30)).until(() -> processed(next) == 1);
    var deadLetters = deadLetters(paymentId, Duration.ofSeconds(10));
    assertThat(deadLetters).hasSize(1);
    assertThat(header(deadLetters.getFirst(), KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
        .isEqualTo(MalformedEventException.class.getName());
    assertThat(merchants.requests(merchant)).hasSize(1);
  }

  @Test
  void anEventForAMerchantWithoutAnEndpointIsRecordedNotSent() throws Exception {
    String merchant = "m_" + UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();

    publish(paymentId, envelope(eventId, paymentId, merchant));

    await().atMost(Duration.ofSeconds(30)).until(() -> processed(eventId) == 1);
    assertThat(delivery(eventId)).containsEntry("status", "NO_ENDPOINT");
    assertThat(merchants.requests(merchant)).isEmpty();
  }
}
