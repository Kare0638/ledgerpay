package com.ledgerpay.payment.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.reconciliation.ReconCategory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gauges of what the database holds (design §13): pending PSP operations and the oldest one,
 * operations set aside for review, quarantined webhooks, the outbox backlog and its age, reserved
 * refund amounts and the latest reconciliation run's breaks per category.
 *
 * <p>One query reads them all, at most every {@value #MAX_AGE_SECONDS} s however many gauges a
 * scrape reads. With the database down they are NaN, so a scrape still answers and the outage shows
 * up as missing data rather than as zero.
 */
@Component
public class OperationalGauges {

  static final long MAX_AGE_SECONDS = 5;
  private static final Logger log = LoggerFactory.getLogger(OperationalGauges.class);

  /** One reading; NaN when the database could not be read. */
  record Snapshot(
      Instant takenAt,
      double pending,
      double oldestPendingSeconds,
      double needsReview,
      double quarantinedWebhooks,
      double unpublishedEvents,
      double outboxLagSeconds,
      double quarantinedEvents,
      double refundReservedMinor,
      Map<ReconCategory, Double> reconItems) {

    static Snapshot unavailable(Instant at) {
      Map<ReconCategory, Double> none = new EnumMap<>(ReconCategory.class);
      for (ReconCategory category : ReconCategory.values()) {
        none.put(category, Double.NaN);
      }
      return new Snapshot(
          at,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          none);
    }
  }

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final Clock clock;
  private volatile Snapshot latest;

  public OperationalGauges(JdbcClient jdbc, ObjectMapper json, Clock clock, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    gauge(
        meters,
        "psp.operations.pending",
        "PSP operations without an outcome yet",
        Snapshot::pending);
    gauge(
        meters,
        "psp.operation.oldest.pending.seconds",
        "Age of the oldest PSP operation without an outcome and not set aside for review; 0 when"
            + " none",
        Snapshot::oldestPendingSeconds);
    gauge(
        meters,
        "psp.operations.needs.review",
        "PSP operations set aside for a human after repeated failures",
        Snapshot::needsReview);
    gauge(
        meters,
        "inbox.quarantined.events",
        "Webhooks quarantined and not yet dealt with",
        Snapshot::quarantinedWebhooks);
    gauge(
        meters,
        "outbox.unpublished.events",
        "Outbox events waiting to be published",
        Snapshot::unpublishedEvents);
    gauge(
        meters,
        "outbox.publish.lag.seconds",
        "Age of the oldest unpublished outbox event; 0 when none",
        Snapshot::outboxLagSeconds);
    gauge(
        meters,
        "outbox.quarantined.events",
        "Outbox events the broker refused for what they are",
        Snapshot::quarantinedEvents);
    gauge(
        meters,
        "refund.reserved.minor",
        "Refund amounts reserved and awaiting the PSP, in minor units",
        Snapshot::refundReservedMinor);
    for (ReconCategory category : ReconCategory.values()) {
      Gauge.builder("recon.breaks", this, g -> g.current().reconItems().get(category))
          .description(
              "Items per category, summed over the latest completed run of each PSP and day")
          .tag("category", category.name())
          .register(meters);
    }
  }

  private void gauge(
      MeterRegistry meters, String name, String description, ToDoubleFunction<Snapshot> value) {
    Gauge.builder(name, this, g -> value.applyAsDouble(g.current()))
        .description(description)
        .register(meters);
  }

  /** The cached reading, or a new one if it is older than {@value #MAX_AGE_SECONDS} s. */
  Snapshot current() {
    Snapshot snapshot = latest;
    Instant now = clock.instant();
    if (snapshot == null
        || Duration.between(snapshot.takenAt(), now).toSeconds() >= MAX_AGE_SECONDS) {
      synchronized (this) {
        snapshot = latest;
        if (snapshot == null
            || Duration.between(snapshot.takenAt(), now).toSeconds() >= MAX_AGE_SECONDS) {
          snapshot = read(now);
          latest = snapshot;
        }
      }
    }
    return snapshot;
  }

  /** Forgets the cached reading, so the next scrape reads again. */
  public void refresh() {
    latest = null;
  }

  private Snapshot read(Instant now) {
    try {
      return jdbc.sql(
              """
              SELECT
                (SELECT count(*) FROM psp_operations WHERE status = 'PENDING') AS pending,
                (SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                   FROM psp_operations WHERE status = 'PENDING' AND NOT needs_review)
                    AS oldest_pending,
                (SELECT count(*) FROM psp_operations
                  WHERE status = 'PENDING' AND needs_review) AS needs_review,
                (SELECT count(*) FROM inbox_events WHERE status = 'QUARANTINED') AS quarantined,
                (SELECT count(*) FROM outbox_events
                  WHERE published_at IS NULL AND quarantined_at IS NULL) AS unpublished,
                (SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0) FROM outbox_events
                  WHERE published_at IS NULL AND quarantined_at IS NULL) AS outbox_lag,
                (SELECT count(*) FROM outbox_events WHERE quarantined_at IS NOT NULL)
                    AS outbox_quarantined,
                (SELECT coalesce(sum(refund_reserved_minor), 0) FROM payments
                  WHERE refund_reserved_minor > 0) AS reserved,
                (SELECT coalesce(jsonb_agg(latest.summary), '[]')
                   FROM (SELECT DISTINCT ON (s.psp, s.business_date) r.summary
                           FROM recon_runs r JOIN settlement_reports s ON s.id = r.report_id
                          WHERE r.status = 'COMPLETED'
                          ORDER BY s.psp, s.business_date, r.finished_at DESC) latest)
                    AS recon""")
          .query(
              (rs, row) ->
                  new Snapshot(
                      now,
                      rs.getDouble("pending"),
                      rs.getDouble("oldest_pending"),
                      rs.getDouble("needs_review"),
                      rs.getDouble("quarantined"),
                      rs.getDouble("unpublished"),
                      rs.getDouble("outbox_lag"),
                      rs.getDouble("outbox_quarantined"),
                      rs.getDouble("reserved"),
                      reconItems(rs.getString("recon"))))
          .single();
    } catch (RuntimeException e) {
      log.debug("Operational gauges unavailable: {}", e.getMessage());
      return Snapshot.unavailable(now);
    }
  }

  /**
   * Items per category, summed over the latest completed run of each PSP and business day: a clean
   * run of one day, or of another PSP, cannot hide breaks still open on another. 0 for every
   * category before the first run.
   */
  private Map<ReconCategory, Double> reconItems(String summaries) {
    Map<ReconCategory, Double> items = new EnumMap<>(ReconCategory.class);
    for (ReconCategory category : ReconCategory.values()) {
      items.put(category, 0.0);
    }
    try {
      for (JsonNode summary : json.readTree(summaries)) {
        JsonNode categories = summary.path("categories");
        for (ReconCategory category : ReconCategory.values()) {
          items.merge(
              category, categories.path(category.name()).path("items").asDouble(0), Double::sum);
        }
      }
    } catch (Exception e) {
      log.warn("Unreadable recon run summaries: {}", e.getMessage());
    }
    return items;
  }
}
