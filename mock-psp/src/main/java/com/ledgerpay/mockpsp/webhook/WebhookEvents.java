package com.ledgerpay.mockpsp.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.mockpsp.operation.Operation;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Webhooks waiting to be sent, written in the same transaction as the outcome they report. */
@Repository
public class WebhookEvents {

  static final int MAX_ERROR_LENGTH = 1000;
  static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

  record Due(String eventId, String payload, int attempts) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public WebhookEvents(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Queues a webhook reporting the operation's current, final state. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(Operation operation) {
    String eventId = "evt_" + UUID.randomUUID();
    String payload;
    try {
      payload = json.writeValueAsString(WebhookPayload.of(eventId, operation));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    jdbc.sql(
            """
            INSERT INTO webhook_events (event_id, psp_reference, payload)
            VALUES (?, ?, ?)""")
        .params(eventId, operation.pspReference(), payload)
        .update();
  }

  /**
   * Claims up to {@code limit} due deliveries in one statement: each is counted as an attempt and
   * hidden for {@code lease}, so a poller that dies mid-send only delays it.
   */
  List<Due> claimDue(int limit, Duration lease, int maxAttempts) {
    return jdbc.sql(
            """
            UPDATE webhook_events
               SET attempts = attempts + 1,
                   next_attempt_at = now() + :leaseMillis * interval '1 millisecond'
             WHERE event_id IN (
                   SELECT event_id FROM webhook_events
                    WHERE delivered_at IS NULL AND next_attempt_at <= now()
                      AND attempts < :maxAttempts
                    ORDER BY next_attempt_at
                    LIMIT :limit
                      FOR UPDATE SKIP LOCKED)
            RETURNING event_id, payload, attempts""")
        .param("leaseMillis", lease.toMillis())
        .param("maxAttempts", maxAttempts)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new Due(rs.getString("event_id"), rs.getString("payload"), rs.getInt("attempts")))
        .list();
  }

  void markDelivered(String eventId) {
    jdbc.sql("UPDATE webhook_events SET delivered_at = now(), last_error = NULL WHERE event_id = ?")
        .param(eventId)
        .update();
  }

  /** Schedules the next attempt 1, 2, 4 … seconds later, capped at {@link #MAX_BACKOFF}. */
  void markFailed(String eventId, int attempts, String error) {
    jdbc.sql(
            """
            UPDATE webhook_events
               SET next_attempt_at = now() + :backoffSeconds * interval '1 second',
                   last_error = :error
             WHERE event_id = :id""")
        .param("backoffSeconds", backoff(attempts).toSeconds())
        .param("error", truncate(error))
        .param("id", eventId)
        .update();
  }

  static Duration backoff(int attempts) {
    int exponent = Math.min(Math.max(attempts - 1, 0), 30);
    Duration delay = Duration.ofSeconds(1L << exponent);
    return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
  }

  private static String truncate(String error) {
    if (error == null) {
      return "unknown error";
    }
    return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
  }
}
