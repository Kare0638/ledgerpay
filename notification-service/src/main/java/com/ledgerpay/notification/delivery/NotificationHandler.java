package com.ledgerpay.notification.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.notification.endpoint.MerchantEndpoint;
import com.ledgerpay.notification.endpoint.MerchantEndpoints;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
  private final MeterRegistry meters;

  NotificationHandler(
      ObjectMapper json,
      Deliveries deliveries,
      MerchantEndpoints endpoints,
      WebhookSender sender,
      MeterRegistry meters) {
    this.meters = meters;
    for (String result : new String[] {"delivered", "no_endpoint", "failed", "duplicate"}) {
      count(result, 0); // registered at 0, for dashboards and alerts
    }
    this.json = json;
    this.deliveries = deliveries;
    this.endpoints = endpoints;
    this.sender = sender;
  }

  /**
   * {@code webhook_deliveries_total{result}} (design §13): delivered, no_endpoint or failed, each
   * attempt counted; {@code duplicate} for an event already handled.
   */
  private void count(String result) {
    count(result, 1);
  }

  private void count(String result, double by) {
    meters.counter("webhook.deliveries", "result", result).increment(by);
  }

  @KafkaListener(topics = "${ledgerpay.notification.topic}")
  void onEvent(String value) {
    PaymentEvent event = PaymentEvent.parse(json, value);
    try (var eventId = MDC.putCloseable("eventId", event.eventId().toString());
        var paymentId = MDC.putCloseable("paymentId", String.valueOf(event.paymentId()))) {
      handle(event);
    }
  }

  private void handle(PaymentEvent event) {
    if (deliveries.isProcessed(event)) {
      log.debug("Event {} already processed; skipped", event.eventId());
      count("duplicate");
      return;
    }
    Optional<MerchantEndpoint> endpoint = endpoints.find(event.merchantId());
    if (endpoint.isEmpty()) {
      log.info(
          "No webhook endpoint for merchant {}; event {} recorded, not sent",
          event.merchantId(),
          event.eventId());
      deliveries.record(event, Deliveries.Status.NO_ENDPOINT, null, null);
      count("no_endpoint");
      return;
    }
    int status;
    try {
      status = sender.send(endpoint.get(), event);
    } catch (RuntimeException e) {
      count("failed");
      throw e;
    }
    count("delivered");
    // A crash or a database failure here sends the webhook again on redelivery: merchants
    // de-duplicate on the event ID header.
    deliveries.record(
        event, Deliveries.Status.DELIVERED, endpoint.get().webhookUrl().toString(), status);
  }
}
