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
import java.util.stream.Collectors;
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
             a.turns, a.tokens, a.precedent_ids, a.created_at, p.id AS proposal_id, p.psp_operation_id, p.reason,
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

  /**
   * Stores an analysis and, if it is COMPLETED and made one, its proposal.
   *
   * @param breakSignature what kind of break it was (BreakSignature)
   * @param embedding the signature's embedding; null when it could not be had
   * @param precedents the earlier analyses it was given, in order
   */
  @Transactional
  UUID insert(
      UUID runId,
      int itemNo,
      String model,
      AgentResult result,
      String breakSignature,
      float[] embedding,
      List<UUID> precedents) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO assistant_analyses (id, run_id, item_no, model, status, analysis, error,
                                            tool_calls, turns, tokens, break_signature,
                                            break_embedding, precedent_ids)
            VALUES (:id, :run, :item, :model, :status, CAST(:analysis AS jsonb), :error,
                    CAST(:calls AS jsonb), :turns, :tokens, :signature,
                    CAST(:embedding AS vector), CAST(:precedents AS uuid[]))""")
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
        .param("signature", breakSignature)
        .param("embedding", embedding == null ? null : vector(embedding))
        .param("precedents", uuids(precedents))
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

  /**
   * The earlier analyses closest to a break (#64): for each other break its latest COMPLETED
   * analysis, unless that analysis' proposal is still open or was rejected. Same category first,
   * then the nearest embedding by cosine distance, then the newest.
   *
   * <p>A break is its operation, else its PSP reference, else its item: one that stays open is an
   * item of every run until it closes, and a report run again repeats all of them. Its analyses in
   * other runs are its own, not precedents, and the latest of them is the one that counts.
   */
  List<Precedent> precedents(
      UUID runId, int itemNo, String category, float[] embedding, int limit) {
    return jdbc.sql(
            """
            WITH analysed AS (
              SELECT a.id, a.created_at, a.break_signature, a.analysis, a.break_embedding,
                     i.category, i.psp_operation_id, i.psp_reference,
                     COALESCE(i.psp_operation_id::text, i.psp_reference,
                              a.run_id || '/' || a.item_no) AS break_key
                FROM assistant_analyses a
                JOIN recon_items i ON i.run_id = a.run_id AND i.item_no = a.item_no
               WHERE a.status = 'COMPLETED'
            ), latest AS (
              SELECT DISTINCT ON (break_key) *
                FROM analysed ORDER BY break_key, created_at DESC
            )
            SELECT l.id, l.break_signature, l.analysis, p.status = 'CONFIRMED' AS confirmed
              FROM latest l
              JOIN recon_items this ON this.run_id = :run AND this.item_no = :item
              LEFT JOIN inquiry_proposals p ON p.analysis_id = l.id
             WHERE l.break_embedding IS NOT NULL
               AND (p.id IS NULL OR p.status = 'CONFIRMED')
               AND l.break_key <> COALESCE(this.psp_operation_id::text, this.psp_reference,
                                           this.run_id || '/' || this.item_no)
               AND (this.psp_operation_id IS NULL
                    OR l.psp_operation_id IS DISTINCT FROM this.psp_operation_id)
               AND (this.psp_reference IS NULL
                    OR l.psp_reference IS DISTINCT FROM this.psp_reference)
             ORDER BY l.category = :category DESC,
                      l.break_embedding <=> CAST(:embedding AS vector),
                      l.created_at DESC
             LIMIT :limit""")
        .param("run", runId)
        .param("item", itemNo)
        .param("category", category)
        .param("embedding", vector(embedding))
        .param("limit", limit)
        .query(
            (rs, row) ->
                new Precedent(
                    rs.getObject("id", UUID.class),
                    rs.getString("break_signature"),
                    fromJson(rs.getString("analysis"), new TypeReference<Analysis>() {}),
                    Boolean.TRUE.equals(rs.getObject("confirmed", Boolean.class))))
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
        List.of((UUID[]) rs.getArray("precedent_ids").getArray()),
        rs.getInt("turns"),
        (Integer) rs.getObject("tokens"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** pgvector's text form, "[0.1,0.2,…]". */
  private static String vector(float[] values) {
    StringBuilder text = new StringBuilder("[");
    for (int i = 0; i < values.length; i++) {
      text.append(i == 0 ? "" : ",").append(values[i]);
    }
    return text.append(']').toString();
  }

  /** Postgres' array text form, "{…,…}". */
  private static String uuids(List<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
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
