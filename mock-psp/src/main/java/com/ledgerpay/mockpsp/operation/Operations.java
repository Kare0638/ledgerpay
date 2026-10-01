package com.ledgerpay.mockpsp.operation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class Operations {

  private static final String COLUMNS =
      """
      psp_reference, psp_request_id, merchant_id, type, parent_reference, amount_minor, currency,
      status, failure_reason, resource_version, created_at, succeeded_at, updated_at""";

  private final JdbcClient jdbc;

  public Operations(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Operation> findByRequestId(String pspRequestId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM operations WHERE psp_request_id = ?")
        .param(pspRequestId)
        .query(Operations::map)
        .optional();
  }

  /** Locks the operation, so decisions about what may follow it are made one at a time. */
  Optional<Operation> lock(String pspReference) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM operations WHERE psp_reference = ? FOR UPDATE")
        .param(pspReference)
        .query(Operations::map)
        .optional();
  }

  /** Pending and successful children of {@code parent} of the given types. */
  List<Operation> activeChildren(String parentReference, List<OperationType> types) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + """
                 FROM operations
                WHERE parent_reference = :parent
                  AND type IN (:types)
                  AND status IN ('PENDING', 'SUCCEEDED')""")
        .param("parent", parentReference)
        .param("types", types.stream().map(Enum::name).toList())
        .query(Operations::map)
        .list();
  }

  /**
   * Inserts a new operation, PENDING until {@code settleAfter} has passed or FAILED straight away.
   * Returns empty if another request with the same {@code psp_request_id} got there first.
   */
  Optional<Operation> insert(
      String pspReference, SubmitRequest request, FailureReason failure, Duration settleAfter) {
    return jdbc.sql(
            """
            INSERT INTO operations
                (psp_reference, psp_request_id, merchant_id, type, parent_reference,
                 amount_minor, currency, status, failure_reason, settle_at)
            VALUES (:reference, :request, :merchant, :type, :parent,
                    :amount, :currency, :status, :failure,
                    now() + :settleMillis * interval '1 millisecond')
            ON CONFLICT (psp_request_id) DO NOTHING
            RETURNING\s"""
                + COLUMNS)
        .param("reference", pspReference)
        .param("request", request.pspRequestId())
        .param("merchant", request.merchantId())
        .param("type", request.type().name())
        .param("parent", request.parentReference())
        .param("amount", request.amountMinor())
        .param("currency", request.currency())
        .param(
            "status", (failure == null ? OperationStatus.PENDING : OperationStatus.FAILED).name())
        .param("failure", failure == null ? null : failure.name())
        .param("settleMillis", settleAfter.toMillis())
        .query(Operations::map)
        .optional();
  }

  /**
   * Moves up to {@code limit} due PENDING operations to SUCCEEDED and returns them. Rows another
   * instance is settling are skipped, never waited for.
   */
  List<Operation> settleDue(int limit) {
    return jdbc.sql(
            """
            UPDATE operations
               SET status = 'SUCCEEDED', succeeded_at = now(), updated_at = now(),
                   resource_version = resource_version + 1
             WHERE psp_reference IN (
                   SELECT psp_reference FROM operations
                    WHERE status = 'PENDING' AND settle_at <= now()
                    ORDER BY settle_at
                    LIMIT :limit
                      FOR UPDATE SKIP LOCKED)
            RETURNING\s"""
                + COLUMNS)
        .param("limit", limit)
        .query(Operations::map)
        .list();
  }

  private static Operation map(ResultSet rs, int row) throws SQLException {
    String failure = rs.getString("failure_reason");
    return new Operation(
        rs.getString("psp_reference"),
        rs.getString("psp_request_id"),
        rs.getString("merchant_id"),
        OperationType.valueOf(rs.getString("type")),
        rs.getString("parent_reference"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        OperationStatus.valueOf(rs.getString("status")),
        failure == null ? null : FailureReason.valueOf(failure),
        rs.getInt("resource_version"),
        instant(rs, "created_at"),
        instant(rs, "succeeded_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
