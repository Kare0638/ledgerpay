package com.ledgerpay.mockpsp.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.mockpsp.MockPspProperties;
import com.ledgerpay.mockpsp.fault.Fault;
import com.ledgerpay.mockpsp.operation.Operation;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Webhooks waiting to be sent, written in the same transaction as the outcome they report. */
@Repository
public class WebhookEvents {

  private static final Logger log = LoggerFactory.getLogger(WebhookEvents.class);

  static final int MAX_ERROR_LENGTH = 1000;
  static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

  record Due(String eventId, String payload, int attempts, int copies) {}

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final MockPspProperties.Faults faults;

  public WebhookEvents(JdbcClient jdbc, ObjectMapper json, MockPspProperties properties) {
    this.jdbc = jdbc;
    this.json = json;
    this.faults = properties.faults();
  }

  /**
   * Queues the webhooks reporting the operation's current, final state: one event, unless a fault
   * was injected for the operation (design §9.4).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(Operation operation) {
    Fault fault = operation.fault();
    if (fault == Fault.DROP_WEBHOOK) {
      log.info("DROP_WEBHOOK: no webhook for {}", operation.pspRequestId());
      return;
    }
    Duration delay = fault == Fault.DELAY_WEBHOOK ? faults.webhookDelay() : Duration.ZERO;
    if (fault == Fault.DUPLICATE_WEBHOOK) {
      // The same event ten times, and the same outcome under two more event IDs: both of the
      // receiver's de-duplication layers get exercised (AT-07).
      insert(WebhookPayload.of(newEventId(), operation), operation, 10, delay, null);
      insert(WebhookPayload.of(newEventId(), operation), operation, 1, delay, null);
      insert(WebhookPayload.of(newEventId(), operation), operation, 1, delay, null);
      return;
    }
    WebhookPayload outcome = WebhookPayload.of(newEventId(), operation);
    insert(outcome, operation, 1, delay, null);
    if (fault == Fault.OUT_OF_ORDER) {
      if (operation.resourceVersion() <= 1) {
        // Failed when submitted: there is no earlier PENDING version to send.
        log.info("OUT_OF_ORDER: {} was never PENDING; no stale event", operation.pspRequestId());
        return;
      }
      // Held back until the outcome is delivered, however many attempts that takes (AT-09).
      insert(
          WebhookPayload.stale(newEventId(), operation),
          operation,
          1,
          faults.staleEventDelay(),
          outcome.eventId());
    }
  }

  private static String newEventId() {
    return "evt_" + UUID.randomUUID();
  }

  private void insert(
      WebhookPayload payload,
      Operation operation,
      int copies,
      Duration delay,
      String afterEventId) {
    String body;
    try {
      body = json.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    jdbc.sql(
            """
            INSERT INTO webhook_events
                (event_id, psp_reference, payload, copies, next_attempt_at, after_event_id)
            VALUES (:id, :reference, :payload, :copies,
                    now() + :delayMillis * interval '1 millisecond', :after)""")
        .param("id", payload.eventId())
        .param("reference", operation.pspReference())
        .param("payload", body)
        .param("copies", copies)
        .param("delayMillis", delay.toMillis())
        .param("after", afterEventId)
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
                   SELECT e.event_id FROM webhook_events e
                    WHERE e.delivered_at IS NULL AND e.next_attempt_at <= now()
                      AND e.attempts < :maxAttempts
                      AND NOT EXISTS (
                          SELECT 1 FROM webhook_events w
                           WHERE w.event_id = e.after_event_id
                             AND w.delivered_at IS NULL)
                    ORDER BY e.next_attempt_at
                    LIMIT :limit
                      FOR UPDATE OF e SKIP LOCKED)
            RETURNING event_id, payload, attempts, copies""")
        .param("leaseMillis", lease.toMillis())
        .param("maxAttempts", maxAttempts)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new Due(
                    rs.getString("event_id"),
                    rs.getString("payload"),
                    rs.getInt("attempts"),
                    rs.getInt("copies")))
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
