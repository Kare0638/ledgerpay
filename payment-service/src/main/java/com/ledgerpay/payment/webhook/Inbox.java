package com.ledgerpay.payment.webhook;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persisted inbound webhooks (design §9.2), de-duplicated on (provider, event_id). */
@Repository
public class Inbox {

  public enum Received {
    NEW,
    /** The same event again, byte for byte: a redelivery. */
    DUPLICATE,
    /** The same event ID with a different body; the original is kept and the body recorded. */
    CONFLICT
  }

  record Pending(String provider, String eventId, String payload) {}

  private final JdbcClient jdbc;

  public Inbox(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores the event as RECEIVED unless it is already there. Never overwrites the original; a
   * different body under the same event ID is recorded in {@code inbox_conflicts} instead.
   */
  @Transactional
  public Received receive(String provider, String eventId, String payload, String payloadHash) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO inbox_events (provider, event_id, payload, payload_hash, status)
                VALUES (:provider, :event, CAST(:payload AS jsonb), :hash, 'RECEIVED')
                ON CONFLICT (provider, event_id) DO NOTHING""")
            .param("provider", provider)
            .param("event", eventId)
            .param("payload", payload)
            .param("hash", payloadHash)
            .update();
    if (inserted == 1) {
      return Received.NEW;
    }
    String stored =
        jdbc.sql("SELECT payload_hash FROM inbox_events WHERE provider = ? AND event_id = ?")
            .params(provider, eventId)
            .query(String.class)
            .single();
    if (stored.equals(payloadHash)) {
      return Received.DUPLICATE;
    }
    jdbc.sql(
            """
            INSERT INTO inbox_conflicts (provider, event_id, payload_hash, payload)
            VALUES (:provider, :event, :hash, :payload)
            ON CONFLICT DO NOTHING""")
        .param("provider", provider)
        .param("event", eventId)
        .param("hash", payloadHash)
        .param("payload", payload)
        .update();
    return Received.CONFLICT;
  }

  /** Locks the oldest RECEIVED event, skipping any another processor holds. */
  @Transactional(propagation = Propagation.MANDATORY)
  Optional<Pending> claimNext() {
    return jdbc.sql(
            """
            SELECT provider, event_id, payload::text AS payload FROM inbox_events
             WHERE status = 'RECEIVED'
             ORDER BY received_at
             LIMIT 1
               FOR UPDATE SKIP LOCKED""")
        .query(
            (rs, row) ->
                new Pending(
                    rs.getString("provider"), rs.getString("event_id"), rs.getString("payload")))
        .optional();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  void finish(String provider, String eventId, String status, String error) {
    jdbc.sql(
            """
            UPDATE inbox_events SET status = :status, error = :error, processed_at = now()
             WHERE provider = :provider AND event_id = :event""")
        .param("status", status)
        .param("error", error)
        .param("provider", provider)
        .param("event", eventId)
        .update();
  }
}
