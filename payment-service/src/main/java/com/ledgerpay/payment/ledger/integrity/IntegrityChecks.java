package com.ledgerpay.payment.ledger.integrity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stored integrity checks; append-only (V10). */
@Repository
public class IntegrityChecks {

  private static final String SELECT =
      """
      SELECT id, trigger, recon_run_id, status, snapshot_at, journals, postings, violation_count,
             violations, checked_at
        FROM ledger_integrity_checks""";

  private static final TypeReference<List<Violation>> VIOLATIONS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public IntegrityChecks(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void insert(IntegrityCheck check) {
    jdbc.sql(
            """
            INSERT INTO ledger_integrity_checks (id, trigger, recon_run_id, status, snapshot_at,
                                                 journals, postings, violation_count, violations,
                                                 checked_at)
            VALUES (:id, :trigger, :run, :status, :snapshot, :journals, :postings, :count,
                    CAST(:violations AS jsonb), :checked)""")
        .param("id", check.checkId())
        .param("trigger", check.trigger().name())
        .param("run", check.reconRunId())
        .param("status", check.status())
        .param("snapshot", check.snapshotAt().atOffset(ZoneOffset.UTC))
        .param("journals", check.journals())
        .param("postings", check.postings())
        .param("count", check.violationCount())
        .param("violations", check.passed() ? null : toJson(check.violations()))
        .param("checked", check.checkedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  public Optional<IntegrityCheck> find(UUID id) {
    return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::map).optional();
  }

  /** The most recent checks, newest first. */
  public List<IntegrityCheck> latest(int limit) {
    return jdbc.sql(SELECT + " ORDER BY checked_at DESC, id LIMIT ?")
        .param(limit)
        .query(this::map)
        .list();
  }

  /**
   * The violation count of the latest stored check, by any instance; one indexed row, cheap enough
   * for every metrics scrape.
   */
  public Optional<Integer> latestViolationCount() {
    return jdbc.sql(
            "SELECT violation_count FROM ledger_integrity_checks"
                + " ORDER BY checked_at DESC, id LIMIT 1")
        .query(Integer.class)
        .optional();
  }

  private IntegrityCheck map(ResultSet rs, int row) throws SQLException {
    String violations = rs.getString("violations");
    return new IntegrityCheck(
        rs.getObject("id", UUID.class),
        IntegrityCheck.Trigger.valueOf(rs.getString("trigger")),
        rs.getObject("recon_run_id", UUID.class),
        rs.getString("status"),
        rs.getObject("snapshot_at", OffsetDateTime.class).toInstant(),
        rs.getLong("journals"),
        rs.getLong("postings"),
        rs.getInt("violation_count"),
        violations == null ? List.of() : fromJson(violations),
        rs.getObject("checked_at", OffsetDateTime.class).toInstant());
  }

  private String toJson(List<Violation> violations) {
    try {
      return json.writeValueAsString(violations);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<Violation> fromJson(String violations) {
    try {
      return json.readValue(violations, VIOLATIONS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
