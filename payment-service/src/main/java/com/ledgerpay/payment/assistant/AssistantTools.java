package com.ledgerpay.payment.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.payment.reconciliation.ReconItem;
import com.ledgerpay.payment.reconciliation.ReconRun;
import com.ledgerpay.payment.settlement.SettlementReports;
import com.ledgerpay.payment.settlement.SettlementRow;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The facts the assistant may read (design §16), each a read-only query returning IDs, statuses,
 * amounts and times; never names, keys or anything outside the books. Nothing here writes. A reply
 * never repeats the caller's arguments: whatever a reply holds counts as a fact the analysis may
 * cite (AnalysisValidator), so an echoed ID would pass as one the books returned.
 */
@Component
class AssistantTools {

  static final String GET_BREAK = "get_break";
  static final String GET_OPERATION_TIMELINE = "get_operation_timeline";
  static final String FIND_OPERATIONS = "find_operations";

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final SettlementReports reports;

  AssistantTools(JdbcClient jdbc, ObjectMapper json, SettlementReports reports) {
    this.jdbc = jdbc;
    this.json = json;
    this.reports = reports;
  }

  /**
   * The break under analysis: its category, report lines, our record as the run saw it, the diff.
   */
  ObjectNode getBreak(ReconRun run, ReconItem item) {
    ObjectNode facts = json.createObjectNode();
    facts.put("runId", run.runId().toString());
    facts.put("itemNo", item.itemNo());
    facts.put("category", item.category().name());
    facts.put("businessDate", run.businessDate().toString());
    facts.put("reportVersion", run.reportVersion());
    facts.put("runSnapshotAt", String.valueOf(run.snapshotAt()));
    facts.put("pspReference", item.pspReference());
    ArrayNode lines = facts.putArray("reportLines");
    for (SettlementRow row : reports.rowsAt(run.reportId(), item.externalLineNos())) {
      ObjectNode line = lines.addObject();
      line.put("line", row.lineNo());
      line.put("pspReference", row.pspReference());
      line.put("pspRequestId", row.pspRequestId());
      line.put("txnType", row.txnType());
      line.put("merchantId", row.merchantId());
      line.put("amountMinor", row.amountMinor());
      line.put("currency", row.currency());
      line.put("succeededAt", row.succeededAt().toString());
    }
    facts.set(
        "ourOperationAtSnapshot",
        item.internal() == null ? json.nullNode() : json.valueToTree(item.internal()));
    facts.set("diff", json.valueToTree(item.diff()));
    return facts;
  }

  /**
   * One of our operations now: its state and retries, its payment, its journals and the webhooks
   * received for it, in time order.
   */
  ObjectNode operationTimeline(UUID operationId) {
    ObjectNode facts = json.createObjectNode();
    var operation =
        jdbc
            .sql(
                """
                SELECT o.id, o.payment_id, o.type, o.status, o.amount_minor, o.currency,
                       o.psp_request_id, o.psp_reference, o.attempts, o.failures, o.needs_review,
                       o.last_error, o.created_at, o.updated_at, o.succeeded_at, o.next_attempt_at,
                       p.status AS payment_status, p.amount_minor AS payment_amount,
                       p.captured_minor, p.refunded_minor, p.refund_reserved_minor
                  FROM psp_operations o JOIN payments p ON p.id = o.payment_id
                 WHERE o.id = ?""")
            .param(operationId)
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    if (operation.isEmpty()) {
      facts.put("error", "no such operation");
      return facts;
    }
    Map<String, Object> row = operation.get();
    ObjectNode op = facts.putObject("operation");
    op.put("pspOperationId", operationId.toString());
    op.put("type", (String) row.get("type"));
    op.put("status", (String) row.get("status"));
    op.put("amountMinor", ((Number) row.get("amount_minor")).longValue());
    op.put("currency", (String) row.get("currency"));
    op.put("pspRequestId", (String) row.get("psp_request_id"));
    op.put("pspReference", (String) row.get("psp_reference"));
    op.put("attempts", ((Number) row.get("attempts")).intValue());
    op.put("consecutiveFailures", ((Number) row.get("failures")).intValue());
    op.put("needsReview", (Boolean) row.get("needs_review"));
    String lastError = (String) row.get("last_error");
    op.put("lastError", lastError == null ? null : Secrets.redact(trim(lastError)));
    op.put("createdAt", time(row.get("created_at")));
    op.put("updatedAt", time(row.get("updated_at")));
    op.put("succeededAt", time(row.get("succeeded_at")));
    op.put("nextAttemptAt", time(row.get("next_attempt_at")));
    ObjectNode payment = facts.putObject("payment");
    payment.put("paymentId", row.get("payment_id").toString());
    payment.put("status", (String) row.get("payment_status"));
    payment.put("amountMinor", ((Number) row.get("payment_amount")).longValue());
    payment.put("capturedMinor", ((Number) row.get("captured_minor")).longValue());
    payment.put("refundedMinor", ((Number) row.get("refunded_minor")).longValue());
    payment.put("refundReservedMinor", ((Number) row.get("refund_reserved_minor")).longValue());

    ArrayNode journals = facts.putArray("journals");
    jdbc.sql(
            "SELECT entry_type, posted_at FROM journal_entries WHERE psp_operation_id = ?"
                + " ORDER BY posted_at")
        .param(operationId)
        .query()
        .listOfRows()
        .forEach(
            j -> {
              ObjectNode entry = journals.addObject();
              entry.put("entryType", (String) j.get("entry_type"));
              entry.put("postedAt", time(j.get("posted_at")));
            });

    ArrayNode webhooks = facts.putArray("webhooksReceived");
    jdbc.sql(
            """
            SELECT payload ->> 'status' AS reported, status, error, received_at
              FROM inbox_events WHERE payload ->> 'psp_request_id' = ?
             ORDER BY received_at LIMIT 20""")
        .param((String) row.get("psp_request_id"))
        .query()
        .listOfRows()
        .forEach(
            w -> {
              ObjectNode event = webhooks.addObject();
              event.put("reportedStatus", (String) w.get("reported"));
              event.put("inboxStatus", (String) w.get("status"));
              String error = (String) w.get("error");
              event.put("note", error == null ? null : trim(error));
              event.put("receivedAt", time(w.get("received_at")));
            });
    return facts;
  }

  /** Our operations with this PSP reference or request ID, at most five. */
  ObjectNode findOperations(String reference) {
    ObjectNode facts = json.createObjectNode();
    ArrayNode matches = facts.putArray("matches");
    List<Map<String, Object>> rows =
        jdbc.sql(
                """
                SELECT id, payment_id, type, status, amount_minor, succeeded_at
                  FROM psp_operations WHERE psp_reference = :ref OR psp_request_id = :ref
                 ORDER BY created_at LIMIT 5""")
            .param("ref", reference)
            .query()
            .listOfRows();
    for (Map<String, Object> row : rows) {
      ObjectNode match = matches.addObject();
      match.put("pspOperationId", row.get("id").toString());
      match.put("paymentId", row.get("payment_id").toString());
      match.put("type", (String) row.get("type"));
      match.put("status", (String) row.get("status"));
      match.put("amountMinor", ((Number) row.get("amount_minor")).longValue());
      match.put("succeededAt", time(row.get("succeeded_at")));
    }
    return facts;
  }

  /** The operation's current status, for checking a proposal; null when there is no such one. */
  String operationStatus(UUID operationId) {
    return jdbc.sql("SELECT status FROM psp_operations WHERE id = ?")
        .param(operationId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  JsonNode error(String message) {
    return json.createObjectNode().put("error", message);
  }

  /** A timestamp column, as the driver hands it back to a row map, in ISO-8601 UTC. */
  private static String time(Object value) {
    return switch (value) {
      case null -> null;
      case Timestamp timestamp -> timestamp.toInstant().toString();
      case OffsetDateTime offset -> offset.toInstant().toString();
      default -> value.toString();
    };
  }

  private static String trim(String text) {
    return text.length() > 200 ? text.substring(0, 200) + "…" : text;
  }
}
