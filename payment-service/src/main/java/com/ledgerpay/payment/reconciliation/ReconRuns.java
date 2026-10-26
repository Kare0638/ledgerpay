package com.ledgerpay.payment.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.reconciliation.ReconItem.Difference;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs and their items. A run is written RUNNING, then finished once (V9 triggers). */
@Repository
public class ReconRuns {

  private static final String SELECT =
      """
      SELECT r.id, r.report_id, s.psp, s.business_date, s.version, r.status, r.rules_version,
             r.snapshot_at, r.summary, r.error, r.created_at, r.finished_at,
             c.id AS check_id, c.status AS check_status, c.violation_count
        FROM recon_runs r JOIN settlement_reports s ON s.id = r.report_id
        LEFT JOIN ledger_integrity_checks c ON c.recon_run_id = r.id""";

  private static final TypeReference<Map<String, Difference>> DIFF = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JdbcTemplate batch;
  private final ObjectMapper json;

  public ReconRuns(JdbcClient jdbc, JdbcTemplate batch, ObjectMapper json) {
    this.jdbc = jdbc;
    this.batch = batch;
    this.json = json;
  }

  public void insertRunning(UUID id, UUID reportId, String rulesVersion) {
    jdbc.sql(
            "INSERT INTO recon_runs (id, report_id, rules_version, status)"
                + " VALUES (?, ?, ?, 'RUNNING')")
        .params(id, reportId, rulesVersion)
        .update();
  }

  public Optional<ReconRun> find(UUID id) {
    return jdbc.sql(SELECT + " WHERE r.id = ?").param(id).query(this::map).optional();
  }

  /** The report's runs, oldest first. */
  public List<ReconRun> forReport(UUID reportId) {
    return jdbc.sql(SELECT + " WHERE r.report_id = ? ORDER BY r.created_at, r.id")
        .param(reportId)
        .query(this::map)
        .list();
  }

  /**
   * Writes the items and finishes the run as COMPLETED, in the caller's transaction. False when the
   * run is no longer RUNNING (the sweeper gave up on it); the caller then rolls back.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean complete(
      UUID runId, Instant snapshotAt, ReconSummary summary, List<ReconItem> items) {
    batch.batchUpdate(
        """
        INSERT INTO recon_items (run_id, item_no, category, psp_reference, psp_operation_id,
                                 external_line_nos, internal, diff)
        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))""",
        items,
        1_000,
        (statement, item) -> {
          statement.setObject(1, runId);
          statement.setInt(2, item.itemNo());
          statement.setString(3, item.category().name());
          statement.setString(4, item.pspReference());
          statement.setObject(5, item.pspOperationId());
          statement.setArray(
              6,
              statement.getConnection().createArrayOf("integer", item.externalLineNos().toArray()));
          statement.setString(7, item.internal() == null ? null : toJson(item.internal()));
          statement.setString(8, item.diff().isEmpty() ? null : toJson(item.diff()));
        });
    return jdbc.sql(
                """
                UPDATE recon_runs
                   SET status = 'COMPLETED', snapshot_at = ?, summary = CAST(? AS jsonb),
                       finished_at = now()
                 WHERE id = ? AND status = 'RUNNING'""")
            .params(snapshotAt.atOffset(ZoneOffset.UTC), toJson(summary), runId)
            .update()
        == 1;
  }

  /** Fails the run if it is still RUNNING; returns whether it was. */
  public boolean fail(UUID runId, String error) {
    return jdbc.sql(
                """
            UPDATE recon_runs SET status = 'FAILED', error = ?, finished_at = now()
             WHERE id = ? AND status = 'RUNNING'""")
            .params(error, runId)
            .update()
        == 1;
  }

  /** The run is still being worked on; the sweeper leaves it alone while this keeps coming. */
  public void heartbeat(UUID runId) {
    jdbc.sql("UPDATE recon_runs SET heartbeat_at = now() WHERE id = ? AND status = 'RUNNING'")
        .param(runId)
        .update();
  }

  /**
   * Marks RUNNING runs whose heartbeat is older than {@code after} as FAILED: whatever was running
   * them has died. Returns how many.
   */
  public int failAbandoned(Duration after) {
    return jdbc.sql(
            """
            UPDATE recon_runs
               SET status = 'FAILED', finished_at = now(),
                   error = 'abandoned: no heartbeat for ' || :seconds || ' s'
             WHERE status = 'RUNNING'
               AND heartbeat_at < now() - :seconds * interval '1 second'""")
        .param("seconds", after.toSeconds())
        .update();
  }

  /** A page of the run's items in item order, optionally of one category. */
  public List<ReconItem> items(UUID runId, ReconCategory category, int offset, int limit) {
    return jdbc.sql(
            """
            SELECT item_no, category, psp_reference, external_line_nos, internal, diff
              FROM recon_items
             WHERE run_id = :run AND (CAST(:category AS text) IS NULL OR category = :category)
             ORDER BY item_no OFFSET :offset LIMIT :limit""")
        .param("run", runId)
        .param("category", category == null ? null : category.name())
        .param("offset", offset)
        .param("limit", limit)
        .query(this::mapItem)
        .list();
  }

  public long countItems(UUID runId, ReconCategory category) {
    return jdbc.sql(
            """
            SELECT count(*) FROM recon_items
             WHERE run_id = :run AND (CAST(:category AS text) IS NULL OR category = :category)""")
        .param("run", runId)
        .param("category", category == null ? null : category.name())
        .query(Long.class)
        .single();
  }

  private ReconRun map(ResultSet rs, int row) throws SQLException {
    String summary = rs.getString("summary");
    return new ReconRun(
        rs.getObject("id", UUID.class),
        rs.getObject("report_id", UUID.class),
        rs.getString("psp"),
        rs.getObject("business_date", LocalDate.class),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getString("rules_version"),
        instant(rs, "snapshot_at"),
        summary == null ? null : fromJson(summary, new TypeReference<ReconSummary>() {}),
        rs.getString("error"),
        instant(rs, "created_at"),
        instant(rs, "finished_at"),
        rs.getObject("check_id") == null
            ? null
            : new ReconRun.LedgerIntegrity(
                rs.getObject("check_id", UUID.class),
                rs.getString("check_status"),
                rs.getInt("violation_count")));
  }

  private ReconItem mapItem(ResultSet rs, int row) throws SQLException {
    Array lines = rs.getArray("external_line_nos");
    String internal = rs.getString("internal");
    String diff = rs.getString("diff");
    return new ReconItem(
        rs.getInt("item_no"),
        ReconCategory.valueOf(rs.getString("category")),
        rs.getString("psp_reference"),
        Arrays.stream((Integer[]) lines.getArray()).toList(),
        internal == null ? null : fromJson(internal, new TypeReference<InternalOperation>() {}),
        diff == null ? Map.of() : fromJson(diff, DIFF));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T fromJson(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
