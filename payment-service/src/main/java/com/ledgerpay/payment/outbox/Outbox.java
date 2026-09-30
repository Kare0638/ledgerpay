package com.ledgerpay.payment.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox (design §10): events are stored in the same transaction as the business
 * change, and a relay (M2) publishes them to Kafka afterwards.
 */
@Repository
public class Outbox {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public Outbox(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public UUID append(UUID aggregateId, String eventType, Object payload) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO outbox_events (id, aggregate_id, event_type, payload)
            VALUES (:id, :aggregate, :type, CAST(:payload AS jsonb))""")
        .param("id", id)
        .param("aggregate", aggregateId)
        .param("type", eventType)
        .param("payload", json.valueToTree(payload).toString())
        .update();
    return id;
  }
}
