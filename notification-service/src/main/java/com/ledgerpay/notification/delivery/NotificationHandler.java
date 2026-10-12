package com.ledgerpay.notification.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.notification.endpoint.MerchantEndpoint;
import com.ledgerpay.notification.endpoint.MerchantEndpoints;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns each payment event into one merchant webhook (design §10). An event already in {@code
 * processed_events} is skipped, so a redelivery from Kafka or a re-send by the outbox relay is
 * handled once. A failure is thrown to the container's error handler, which retries it and then
 * dead-letters it; the offset is committed only once the record is handled either way.
 */
@Component
class NotificationHandler {

  private static final Logger log = LoggerFactory.getLogger(NotificationHandler.class);

  private final ObjectMapper json;
  private final Deliveries deliveries;
  private final MerchantEndpoints endpoints;
  private final WebhookSender sender;

  NotificationHandler(
      ObjectMapper json, Deliveries deliveries, MerchantEndpoints endpoints, WebhookSender sender) {
    this.json = json;
    this.deliveries = deliveries;
    this.endpoints = endpoints;
    this.sender = sender;
  }

  @KafkaListener(topics = "${ledgerpay.notification.topic}")
  void onEvent(String value) {
    PaymentEvent event = PaymentEvent.parse(json, value);
    if (deliveries.isProcessed(event)) {
      log.debug("Event {} already processed; skipped", event.eventId());
      return;
    }
    Optional<MerchantEndpoint> endpoint = endpoints.find(event.merchantId());
    if (endpoint.isEmpty()) {
      log.info(
          "No webhook endpoint for merchant {}; event {} recorded, not sent",
          event.merchantId(),
          event.eventId());
      deliveries.record(event, Deliveries.Status.NO_ENDPOINT, null, null);
      return;
    }
    int status = sender.send(endpoint.get(), event);
    // A crash or a database failure here sends the webhook again on redelivery: merchants
    // de-duplicate on the event ID header.
    deliveries.record(
        event, Deliveries.Status.DELIVERED, endpoint.get().webhookUrl().toString(), status);
  }
}
