package com.ledgerpay.payment.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads our side of a run in one {@code REPEATABLE READ} read-only transaction (design §11.2), so
 * every query sees the books as of one moment, {@code snapshot_at}, whatever commits meanwhile.
 *
 * <p>The internal set is every capture and refund that succeeded on the business day, plus every
 * operation the report refers to by reference or request ID, whatever its type or status: that is
 * how a row whose operation is still PENDING here is found.
 */
@Component
public class BooksSnapshot {

  public record Snapshot(Instant takenAt, List<InternalOperation> operations) {}

  private static final String SELECT =
      """
      SELECT o.id, o.payment_id, p.merchant_id, o.type, o.psp_request_id, o.psp_reference,
             o.amount_minor, o.currency, o.status, o.succeeded_at
        FROM psp_operations o JOIN payments p ON p.id = o.payment_id""";

  private final JdbcClient jdbc;
  private final TransactionTemplate snapshot;
  private final ObjectProvider<SnapshotProbe> probe;

  public BooksSnapshot(
      JdbcClient jdbc,
      PlatformTransactionManager transactions,
      ObjectProvider<SnapshotProbe> probe) {
    this.jdbc = jdbc;
    this.snapshot = new TransactionTemplate(transactions);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshot.setReadOnly(true);
    this.probe = probe;
  }

  public Snapshot read(UUID runId, UUID reportId, LocalDate businessDay) {
    return snapshot.execute(
        status -> {
          // The first statement fixes the transaction's snapshot; its clock is snapshot_at.
          Instant takenAt =
              jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
          Map<UUID, InternalOperation> operations = new LinkedHashMap<>();
          jdbc.sql(
                  SELECT
                      + "\n"
                      + """
                       WHERE o.type IN ('CAPTURE', 'REFUND') AND o.status = 'SUCCEEDED'
                         AND o.succeeded_at >= ? AND o.succeeded_at < ?""")
              .params(
                  businessDay.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(),
                  businessDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime())
              .query(BooksSnapshot::map)
              .list()
              .forEach(operation -> operations.put(operation.id(), operation));
          probe.ifAvailable(p -> p.betweenReads(runId));
          jdbc.sql(
                  SELECT
                      + "\n"
                      + """
                       WHERE o.psp_reference IN
                             (SELECT psp_reference FROM settlement_rows WHERE report_id = :report)
                          OR o.psp_request_id IN
                             (SELECT psp_request_id FROM settlement_rows WHERE report_id = :report)""")
              .param("report", reportId)
              .query(BooksSnapshot::map)
              .list()
              .forEach(operation -> operations.putIfAbsent(operation.id(), operation));
          return new Snapshot(takenAt, List.copyOf(operations.values()));
        });
  }

  private static InternalOperation map(ResultSet rs, int row) throws SQLException {
    OffsetDateTime succeededAt = rs.getObject("succeeded_at", OffsetDateTime.class);
    return new InternalOperation(
        rs.getObject("id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getString("merchant_id"),
        rs.getString("type"),
        rs.getString("psp_request_id"),
        rs.getString("psp_reference"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getString("status"),
        succeededAt == null ? null : succeededAt.toInstant());
  }
}
