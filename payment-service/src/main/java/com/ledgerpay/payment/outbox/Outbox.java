package com.ledgerpay.payment.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox (design §10): events are stored in the same transaction as the business
 * change, and {@link OutboxRelay} publishes them to Kafka afterwards.
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

  /**
   * Locks up to {@code limit} unpublished events for publishing, oldest first. Only the earliest
   * unpublished event of each payment is eligible: a payment's next event waits until this one is
   * published, so concurrent relays never put one payment's events out of order. {@code SKIP
   * LOCKED} lets them take other payments' events meanwhile. A relay that sees an earlier event
   * still unpublished, because another relay holds it, skips the payment for this round. A
   * quarantined event is never claimed, and as it stays unpublished it holds back the rest of its
   * payment until a human has dealt with it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<OutboxEvent> claimUnpublished(int limit) {
    return jdbc.sql(
            """
            SELECT id, seq, aggregate_id, event_type, payload::text AS payload, created_at
              FROM outbox_events e
             WHERE published_at IS NULL AND quarantined_at IS NULL
               AND NOT EXISTS (
                   SELECT 1 FROM outbox_events earlier
                    WHERE earlier.aggregate_id = e.aggregate_id
                      AND earlier.published_at IS NULL
                      AND earlier.seq < e.seq)
             ORDER BY seq
             LIMIT :limit
               FOR UPDATE SKIP LOCKED""")
        .param("limit", limit)
        .query(
            (rs, row) ->
                new OutboxEvent(
                    rs.getObject("id", UUID.class),
                    rs.getLong("seq"),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * Counts a refusal of the event for what it is; at {@code maxRejections} it is quarantined.
   * Returns whether it now is.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean recordRejection(UUID id, String error, int maxRejections) {
    return jdbc.sql(
            """
            UPDATE outbox_events
               SET rejections = rejections + 1, last_error = :error,
                   quarantined_at = CASE WHEN rejections + 1 >= :max THEN now() END
             WHERE id = :id
            RETURNING quarantined_at IS NOT NULL""")
        .param("error", error.length() <= 1000 ? error : error.substring(0, 1000))
        .param("max", maxRejections)
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markPublished(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id IN (:ids)")
        .param("ids", ids)
        .update();
  }
}
