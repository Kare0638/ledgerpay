package com.ledgerpay.notification.delivery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code processed_events} and {@code webhook_deliveries}, written together. */
@Repository
class Deliveries {

  enum Status {
    DELIVERED,
    NO_ENDPOINT
  }

  private final JdbcClient jdbc;

  Deliveries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean isProcessed(PaymentEvent event) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = ?)")
        .param(event.eventId())
        .query(Boolean.class)
        .single();
  }

  /**
   * Marks the event processed and records what happened to it, in one transaction. Returns false,
   * writing nothing, if the event was already processed.
   */
  @Transactional
  public boolean record(PaymentEvent event, Status status, String url, Integer responseStatus) {
    int inserted =
        jdbc.sql("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING")
            .param(event.eventId())
            .update();
    if (inserted == 0) {
      return false;
    }
    jdbc.sql(
            """
            INSERT INTO webhook_deliveries
                   (event_id, merchant_id, event_type, payment_id, status, webhook_url,
                    response_status)
            VALUES (:event, :merchant, :type, :payment, :status, :url, :response)""")
        .param("event", event.eventId())
        .param("merchant", event.merchantId())
        .param("type", event.eventType())
        .param("payment", event.paymentId())
        .param("status", status.name())
        .param("url", url)
        .param("response", responseStatus)
        .update();
    return true;
  }
}
