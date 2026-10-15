package com.ledgerpay.payment.settlement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Settlement reports and their rows; append-only, as the table triggers enforce (V8). */
@Repository
public class SettlementReports {

  private static final String SELECT =
      """
      SELECT id, psp, business_date, version, status, row_count, error_count, errors, file_sha256,
             uploaded_at
        FROM settlement_reports""";

  private static final TypeReference<List<LineError>> ERRORS = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JdbcTemplate batch;
  private final ObjectMapper json;

  public SettlementReports(JdbcClient jdbc, JdbcTemplate batch, ObjectMapper json) {
    this.jdbc = jdbc;
    this.batch = batch;
    this.json = json;
  }

  public Optional<SettlementReport> find(UUID id) {
    return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(this::map).optional();
  }

  public Optional<SettlementReport> findByFile(String psp, LocalDate businessDate, String sha256) {
    return jdbc.sql(SELECT + " WHERE psp = ? AND business_date = ? AND file_sha256 = ?")
        .params(psp, businessDate, sha256)
        .query(this::map)
        .optional();
  }

  /** Every version for the day, oldest first. */
  public List<SettlementReport> versions(String psp, LocalDate businessDate) {
    return jdbc.sql(SELECT + " WHERE psp = ? AND business_date = ? ORDER BY version")
        .params(psp, businessDate)
        .query(this::map)
        .list();
  }

  /**
   * Serialises uploads for one PSP and day until the transaction ends, so two new files cannot take
   * the same version number.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockDay(String psp, LocalDate businessDate) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param("settlement:" + psp + ":" + businessDate)
        .query()
        .singleRow();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int nextVersion(String psp, LocalDate businessDate) {
    return jdbc.sql(
            "SELECT coalesce(max(version), 0) + 1 FROM settlement_reports"
                + " WHERE psp = ? AND business_date = ?")
        .params(psp, businessDate)
        .query(Integer.class)
        .single();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(SettlementReport report, List<SettlementRow> rows) {
    jdbc.sql(
            """
            INSERT INTO settlement_reports (id, psp, business_date, file_sha256, version, status,
                                            row_count, error_count, errors, uploaded_at)
            VALUES (:id, :psp, :date, :sha, :version, :status, :rows, :errorCount,
                    CAST(:errors AS jsonb), :uploadedAt)""")
        .param("id", report.reportId())
        .param("psp", report.psp())
        .param("date", report.businessDate())
        .param("sha", report.fileSha256())
        .param("version", report.version())
        .param("status", report.status())
        .param("rows", report.rowCount())
        .param("errorCount", report.errorCount())
        .param("errors", report.valid() ? null : toJson(report.errors()))
        .param("uploadedAt", report.uploadedAt().atOffset(ZoneOffset.UTC))
        .update();
    batch.batchUpdate(
        """
        INSERT INTO settlement_rows (report_id, line_no, psp_reference, psp_request_id, txn_type,
                                     merchant_id, amount_minor, currency, succeeded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        rows,
        1_000,
        (statement, row) -> {
          statement.setObject(1, report.reportId());
          statement.setInt(2, row.lineNo());
          statement.setString(3, row.pspReference());
          statement.setString(4, row.pspRequestId());
          statement.setString(5, row.txnType());
          statement.setString(6, row.merchantId());
          statement.setLong(7, row.amountMinor());
          statement.setString(8, row.currency());
          statement.setObject(9, row.succeededAt().atOffset(ZoneOffset.UTC));
        });
  }

  /** A report's rows in file order. */
  public List<SettlementRow> rows(UUID reportId) {
    return jdbc.sql(
            """
            SELECT line_no, psp_reference, psp_request_id, txn_type, merchant_id, amount_minor,
                   currency, succeeded_at
              FROM settlement_rows WHERE report_id = ? ORDER BY line_no""")
        .param(reportId)
        .query(
            (rs, row) ->
                new SettlementRow(
                    rs.getInt("line_no"),
                    rs.getString("psp_reference"),
                    rs.getString("psp_request_id"),
                    rs.getString("txn_type"),
                    rs.getString("merchant_id"),
                    rs.getLong("amount_minor"),
                    rs.getString("currency"),
                    rs.getObject("succeeded_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  private SettlementReport map(ResultSet rs, int row) throws SQLException {
    String errors = rs.getString("errors");
    Timestamp uploaded = rs.getTimestamp("uploaded_at");
    return new SettlementReport(
        rs.getObject("id", UUID.class),
        rs.getString("psp"),
        rs.getObject("business_date", LocalDate.class),
        rs.getInt("version"),
        rs.getString("status"),
        rs.getInt("row_count"),
        rs.getInt("error_count"),
        errors == null ? List.of() : fromJson(errors),
        rs.getString("file_sha256"),
        uploaded.toInstant());
  }

  private String toJson(List<LineError> errors) {
    try {
      return json.writeValueAsString(errors);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<LineError> fromJson(String errors) {
    try {
      return json.readValue(errors, ERRORS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
