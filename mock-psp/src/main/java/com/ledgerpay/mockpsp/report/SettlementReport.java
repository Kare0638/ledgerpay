package com.ledgerpay.mockpsp.report;

import com.ledgerpay.common.csv.Csv;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The daily settlement report (design §11.1), built from mock-psp's own operations and nothing
 * else: payment-service's books never feed into it, so the two can disagree. Only successful
 * captures and refunds move money, so only they are listed. The business day is UTC, {@code [D
 * 00:00Z, D+1 00:00Z)}, by when the operation succeeded (ADR 0007).
 */
@Component
public class SettlementReport {

  public static final List<String> COLUMNS =
      List.of(
          "psp_reference",
          "psp_request_id",
          "txn_type",
          "merchant_id",
          "amount_minor",
          "currency",
          "succeeded_at");

  private final JdbcClient jdbc;

  SettlementReport(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The day's report as CSV text: a header, then one row per operation in success order. */
  public String csv(LocalDate businessDay) {
    Instant from = businessDay.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = businessDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    var out = new StringBuilder(Csv.row(COLUMNS));
    jdbc
        .sql(
            """
            SELECT psp_reference, psp_request_id, type, merchant_id, amount_minor, currency,
                   succeeded_at
              FROM operations
             WHERE status = 'SUCCEEDED' AND type IN ('CAPTURE', 'REFUND')
               AND succeeded_at >= ? AND succeeded_at < ?
             ORDER BY succeeded_at, psp_reference""")
        .params(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC))
        .query(
            (rs, row) ->
                List.of(
                    rs.getString("psp_reference"),
                    rs.getString("psp_request_id"),
                    rs.getString("type"),
                    rs.getString("merchant_id"),
                    Long.toString(rs.getLong("amount_minor")),
                    rs.getString("currency"),
                    rs.getObject("succeeded_at", OffsetDateTime.class).toInstant().toString()))
        .stream()
        .forEach(cells -> out.append(Csv.row(cells)));
    return out.toString();
  }
}
