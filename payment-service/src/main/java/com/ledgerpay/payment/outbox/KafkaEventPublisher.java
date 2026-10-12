package com.ledgerpay.payment.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to {@code payments.events}, keyed by payment ID so one payment's events stay on one
 * partition. The producer runs with {@code acks=all} and {@code enable.idempotence=true}
 * (application.yml): an acknowledged event is on every in-sync replica, and the producer's own
 * retries do not duplicate it. The relay's retries can, which is why consumers de-duplicate.
 *
 * <p>The value is an envelope: {@code eventId}, {@code eventType}, {@code paymentId}, {@code
 * occurredAt} and the event itself as {@code data}. {@code eventId} and {@code eventType} are also
 * headers, so a consumer can skip a duplicate without parsing the body.
 */
@Component
class KafkaEventPublisher implements EventPublisher {

  static final String EVENT_ID_HEADER = "eventId";
  static final String EVENT_TYPE_HEADER = "eventType";

  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper json;
  private final String topic;

  KafkaEventPublisher(
      KafkaTemplate<String, String> kafka, ObjectMapper json, OutboxProperties properties) {
    this.kafka = kafka;
    this.json = json;
    this.topic = properties.topic();
  }

  @Override
  public CompletableFuture<Void> publish(OutboxEvent event) {
    var record = new ProducerRecord<>(topic, event.aggregateId().toString(), envelope(event));
    record.headers().add(EVENT_ID_HEADER, bytes(event.id().toString()));
    record.headers().add(EVENT_TYPE_HEADER, bytes(event.eventType()));
    return kafka.send(record).thenApply(result -> null);
  }

  private String envelope(OutboxEvent event) {
    ObjectNode envelope = json.createObjectNode();
    envelope.put("eventId", event.id().toString());
    envelope.put("eventType", event.eventType());
    envelope.put("paymentId", event.aggregateId().toString());
    envelope.put("occurredAt", event.createdAt().toString());
    try {
      envelope.set("data", json.readTree(event.payload()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Outbox event " + event.id() + " is not JSON", e);
    }
    return envelope.toString();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
