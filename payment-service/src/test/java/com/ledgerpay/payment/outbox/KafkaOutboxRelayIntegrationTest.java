package com.ledgerpay.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.KafkaTestcontainersConfiguration;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.payment.PaymentService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

/** The relay with the real producer against a Kafka broker in Testcontainers. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.kafka.admin.auto-create=true")
@Import({PostgresTestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class KafkaOutboxRelayIntegrationTest {

  @Autowired OutboxRelay relay;
  @Autowired Outbox outbox;
  @Autowired EventPublisher publisher;
  @Autowired OutboxProperties properties;
  @Autowired PaymentService payments;
  @Autowired KafkaContainer kafka;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired TransactionTemplate tx;

  KafkaConsumer<String, String> consumer;

  @BeforeEach
  void readTheTopicFromTheStart() {
    jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL").update();
    consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    List<TopicPartition> partitions =
        consumer.partitionsFor(properties.topic()).stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
    consumer.assign(partitions);
    consumer.seekToBeginning(partitions);
  }

  @AfterEach
  void close() {
    consumer.close();
  }

  String bootstrapServers() {
    return kafka.getBootstrapServers();
  }

  UUID createPayment() {
    String merchant = "m_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchant, "hash_" + merchant)
        .update();
    return tx.execute(
        status ->
            payments.create(merchant, "ref_" + UUID.randomUUID(), Money.of(2_500, Money.GBP)).id());
  }

  UUID eventId(UUID paymentId) {
    return jdbc.sql("SELECT id FROM outbox_events WHERE aggregate_id = ?")
        .param(paymentId)
        .query(UUID.class)
        .single();
  }

  boolean published(UUID eventId) {
    return jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?")
        .param(eventId)
        .query(Boolean.class)
        .single();
  }

  /** Everything read from the topic so far, in the order of each partition. */
  final List<ConsumerRecord<String, String>> consumed = new ArrayList<>();

  /** The payment's records, reading the topic until {@code count} have arrived. */
  List<ConsumerRecord<String, String>> records(UUID paymentId, int count) {
    Instant deadline = Instant.now().plusSeconds(20);
    while (keyedBy(paymentId).size() < count && Instant.now().isBefore(deadline)) {
      consumer.poll(Duration.ofMillis(200)).forEach(consumed::add);
    }
    // A duplicate would be on the same partition right behind; one more poll to catch it.
    consumer.poll(Duration.ofMillis(500)).forEach(consumed::add);
    return keyedBy(paymentId);
  }

  List<ConsumerRecord<String, String>> keyedBy(UUID paymentId) {
    return consumed.stream().filter(record -> record.key().equals(paymentId.toString())).toList();
  }

  static String header(ConsumerRecord<String, String> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  @Test
  void publishesTheEventKeyedByPaymentWithItsIdAndMarksItPublished() throws Exception {
    UUID paymentId = createPayment();
    UUID eventId = eventId(paymentId);

    assertThat(relay.relayAll()).isEqualTo(1);

    var records = records(paymentId, 1);
    assertThat(records).hasSize(1);
    var record = records.getFirst();
    assertThat(header(record, KafkaEventPublisher.EVENT_ID_HEADER)).isEqualTo(eventId.toString());
    assertThat(header(record, KafkaEventPublisher.EVENT_TYPE_HEADER))
        .isEqualTo(PaymentService.PAYMENT_CREATED);
    JsonNode envelope = json.readTree(record.value());
    assertThat(envelope.path("eventId").asText()).isEqualTo(eventId.toString());
    assertThat(envelope.path("eventType").asText()).isEqualTo(PaymentService.PAYMENT_CREATED);
    assertThat(envelope.path("paymentId").asText()).isEqualTo(paymentId.toString());
    assertThat(envelope.path("data").path("amountMinor").asLong()).isEqualTo(2_500);
    assertThat(published(eventId)).isTrue();
  }

  /**
   * Done when for #14: the relay dies after Kafka acknowledged but before it marked the event, and
   * the restarted relay publishes it again. The consumer sees the same eventId twice.
   */
  @Test
  void aRestartedRelayRepublishesWhatWasNotMarkedPublished() {
    UUID paymentId = createPayment();
    UUID eventId = eventId(paymentId);
    Outbox crashing =
        new Outbox(jdbc, json) {
          @Override
          public void markPublished(Collection<UUID> ids) {
            throw new IllegalStateException("relay killed before commit");
          }
        };
    OutboxRelay dying = new OutboxRelay(crashing, publisher, tx, properties);

    assertThatThrownBy(dying::relayAll).hasMessageContaining("killed before commit");
    assertThat(published(eventId)).isFalse();

    OutboxRelay restarted = new OutboxRelay(outbox, publisher, tx, properties);
    assertThat(restarted.relayAll()).isEqualTo(1);

    var records = records(paymentId, 2);
    assertThat(records)
        .extracting(record -> header(record, KafkaEventPublisher.EVENT_ID_HEADER))
        .containsExactly(eventId.toString(), eventId.toString());
    assertThat(published(eventId)).isTrue();
  }

  /**
   * An event over the producer's max.request.size (1 MB) fails at once, like a missing broker, but
   * is only that event's problem: other payments' events in the batch are published, and after
   * max-rejections runs the event is quarantined.
   */
  @Test
  void anEventTooLargeForKafkaIsQuarantinedWithoutHoldingBackOtherPayments() {
    UUID tooLarge =
        tx.execute(
            status ->
                outbox.append(
                    UUID.randomUUID(), "PaymentCreated", Map.of("blob", "x".repeat(1_100_000))));
    UUID paymentId = createPayment();

    assertThat(relay.relayAll()).isEqualTo(1);
    assertThat(records(paymentId, 1)).hasSize(1);
    for (int run = 1; run < properties.maxRejections(); run++) {
      assertThat(relay.relayAll()).isZero();
    }

    Map<String, Object> state =
        jdbc.sql(
                """
                SELECT rejections, last_error, quarantined_at IS NOT NULL AS quarantined
                  FROM outbox_events WHERE id = ?""")
            .param(tooLarge)
            .query()
            .singleRow();
    assertThat(state)
        .containsEntry("rejections", properties.maxRejections())
        .containsEntry("quarantined", true);
    assertThat((String) state.get("last_error")).contains("RecordTooLargeException");
    assertThat(published(tooLarge)).isFalse();
  }

  /**
   * With no broker the relay fails fast, the payment is unaffected and the event waits in the
   * table; once Kafka is reachable it is published.
   */
  @Test
  void eventsWaitInTheTableWhileKafkaIsUnreachable() {
    var unreachable =
        new KafkaTemplate<String, String>(
            new DefaultKafkaProducerFactory<>(
                Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    "localhost:1",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG,
                    1_000)));
    OutboxRelay down =
        new OutboxRelay(
            outbox, new KafkaEventPublisher(unreachable, json, properties), tx, properties);
    List<UUID> paymentIds = List.of(createPayment(), createPayment(), createPayment());

    // Each send waits up to max.block.ms (1 s) for metadata; the relay stops at the first, so the
    // batch does not hold its rows for a second per event.
    long started = System.nanoTime();
    assertThat(down.relayAll()).isZero();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_500));
    assertThat(paymentIds).noneMatch(id -> published(eventId(id)));
    unreachable.destroy();

    assertThat(relay.relayAll()).isEqualTo(3);
    assertThat(paymentIds).allSatisfy(id -> assertThat(records(id, 1)).hasSize(1));
  }
}
