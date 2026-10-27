package com.ledgerpay.payment.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Analyses (append-only) and inquiry proposals (decided once), V12. */
@Repository
class AssistantStore {

  private static final String SELECT =
      """
      SELECT a.id, a.run_id, a.item_no, a.model, a.status, a.analysis, a.error, a.tool_calls,
             a.turns, a.tokens, a.created_at, p.id AS proposal_id, p.psp_operation_id, p.reason,
             p.status AS proposal_status, p.decided_by, p.decided_at, p.outcome
        FROM assistant_analyses a LEFT JOIN inquiry_proposals p ON p.analysis_id = a.id""";

  private static final TypeReference<List<AgentResult.ToolCall>> CALLS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final Clock clock;

  AssistantStore(JdbcClient jdbc, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  /** Stores an analysis and, if it is COMPLETED and made one, its proposal. */
  @Transactional
  UUID insert(UUID runId, int itemNo, String model, AgentResult result) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO assistant_analyses (id, run_id, item_no, model, status, analysis, error,
                                            tool_calls, turns, tokens)
            VALUES (:id, :run, :item, :model, :status, CAST(:analysis AS jsonb), :error,
                    CAST(:calls AS jsonb), :turns, :tokens)""")
        .param("id", id)
        .param("run", runId)
        .param("item", itemNo)
        .param("model", model)
        .param("status", result.status().name())
        .param("analysis", result.analysis() == null ? null : toJson(result.analysis()))
        .param("error", result.error())
        .param("calls", toJson(result.toolCalls()))
        .param("turns", result.turns())
        .param("tokens", result.tokens())
        .update();
    if (result.status() == AgentResult.Status.COMPLETED && result.proposal() != null) {
      jdbc.sql(
              """
              INSERT INTO inquiry_proposals (id, analysis_id, psp_operation_id, reason, status)
              VALUES (?, ?, ?, ?, 'PROPOSED')""")
          .params(
              UUID.randomUUID(), id, result.proposal().pspOperationId(), result.proposal().reason())
          .update();
    }
    return id;
  }

  Optional<StoredAnalysis> find(UUID analysisId) {
    return jdbc.sql(SELECT + " WHERE a.id = ?").param(analysisId).query(this::map).optional();
  }

  Optional<StoredAnalysis> findByProposal(UUID proposalId) {
    return jdbc.sql(SELECT + " WHERE p.id = ?").param(proposalId).query(this::map).optional();
  }

  /** The analyses of a run's item, newest first. */
  List<StoredAnalysis> forItem(UUID runId, int itemNo) {
    return jdbc.sql(SELECT + " WHERE a.run_id = ? AND a.item_no = ? ORDER BY a.created_at DESC")
        .params(runId, itemNo)
        .query(this::map)
        .list();
  }

  /** The proposal's status, locked until the transaction ends; empty if there is none. */
  @Transactional(propagation = Propagation.MANDATORY)
  Optional<String> lockProposal(UUID proposalId) {
    return jdbc.sql("SELECT status FROM inquiry_proposals WHERE id = ? FOR UPDATE")
        .param(proposalId)
        .query(String.class)
        .optional();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  void decide(UUID proposalId, String status, String decidedBy, String outcome) {
    jdbc.sql(
            """
            UPDATE inquiry_proposals SET status = ?, decided_by = ?, decided_at = ?, outcome = ?
             WHERE id = ? AND status = 'PROPOSED'""")
        .params(
            status,
            decidedBy,
            clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
            outcome,
            proposalId)
        .update();
  }

  private StoredAnalysis map(ResultSet rs, int row) throws SQLException {
    String analysis = rs.getString("analysis");
    UUID proposalId = rs.getObject("proposal_id", UUID.class);
    return new StoredAnalysis(
        rs.getObject("id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getInt("item_no"),
        rs.getString("model"),
        rs.getString("status"),
        analysis == null ? null : fromJson(analysis, new TypeReference<Analysis>() {}),
        rs.getString("error"),
        proposalId == null
            ? null
            : new StoredAnalysis.StoredProposal(
                proposalId,
                rs.getObject("psp_operation_id", UUID.class),
                rs.getString("reason"),
                rs.getString("proposal_status"),
                rs.getString("decided_by"),
                instant(rs, "decided_at"),
                rs.getString("outcome")),
        fromJson(rs.getString("tool_calls"), CALLS),
        rs.getInt("turns"),
        (Integer) rs.getObject("tokens"),
        instant(rs, "created_at"));
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
