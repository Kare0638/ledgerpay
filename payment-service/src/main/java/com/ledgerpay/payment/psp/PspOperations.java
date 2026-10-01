package com.ledgerpay.payment.psp;

import com.ledgerpay.common.money.Money;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persisted PSP calls (design §5.2). A new operation is PENDING and due immediately; the worker
 * (#7) makes the actual call, outside this transaction.
 */
@Repository
public class PspOperations {

  private final JdbcClient jdbc;

  public PspOperations(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records a pending operation with a fixed {@code psp_request_id}. The request ID never changes
   * across retries, so the PSP can de-duplicate and money can never move twice.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID createPending(UUID paymentId, PspOperationType type, Money amount) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO psp_operations
                (id, payment_id, type, psp_request_id, amount_minor, currency, status)
            VALUES (:id, :payment, :type, :request, :amount, :currency, 'PENDING')""")
        .param("id", id)
        .param("payment", paymentId)
        .param("type", type.name())
        .param("request", "req_" + id)
        .param("amount", amount.minor())
        .param("currency", amount.currency().getCurrencyCode())
        .update();
    return id;
  }

  /**
   * Claims up to {@code limit} due operations in one short statement (design §9.1): each gets a
   * lease and an attempt. {@code SKIP LOCKED} means concurrent workers take disjoint batches
   * instead of waiting; the lease keeps an operation away from them while its PSP call runs.
   */
  public List<ClaimedOperation> claimDue(int limit, Duration lease) {
    return jdbc.sql(
            """
            WITH claimed AS (
                UPDATE psp_operations
                   SET lease_until = now() + :leaseMillis * interval '1 millisecond',
                       attempts = attempts + 1,
                       updated_at = now()
                 WHERE id IN (
                       SELECT id FROM psp_operations
                        WHERE status = 'PENDING' AND next_attempt_at <= now()
                          AND (lease_until IS NULL OR lease_until < now())
                        ORDER BY next_attempt_at
                        LIMIT :limit
                          FOR UPDATE SKIP LOCKED)
                RETURNING id, payment_id, type, psp_request_id, amount_minor, currency,
                          psp_reference, attempts)
            SELECT c.*, p.merchant_id,
                   (SELECT parent.psp_reference FROM psp_operations parent
                     WHERE parent.payment_id = c.payment_id AND parent.status = 'SUCCEEDED'
                       AND parent.type = CASE c.type WHEN 'REFUND' THEN 'CAPTURE'
                                                     ELSE 'AUTHORIZE' END
                     LIMIT 1) AS parent_reference
              FROM claimed c JOIN payments p ON p.id = c.payment_id
             ORDER BY c.id""")
        .param("leaseMillis", lease.toMillis())
        .param("limit", limit)
        .query(
            (rs, row) ->
                new ClaimedOperation(
                    rs.getObject("id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    PspOperationType.valueOf(rs.getString("type")),
                    rs.getString("psp_request_id"),
                    rs.getString("merchant_id"),
                    rs.getLong("amount_minor"),
                    rs.getString("currency"),
                    rs.getString("psp_reference"),
                    rs.getString("parent_reference"),
                    rs.getInt("attempts")))
        .list();
  }

  /**
   * The PSP has the operation: keep its reference, release the lease and schedule the safety-net
   * inquiry in case the webhook never arrives. No-op once the operation has an outcome.
   */
  public void markAccepted(UUID id, String pspReference, Duration safetyNetDelay) {
    jdbc.sql(
            """
            UPDATE psp_operations
               SET psp_reference = :reference,
                   next_attempt_at = now() + :delayMillis * interval '1 millisecond',
                   lease_until = NULL, last_error = NULL, updated_at = now()
             WHERE id = :id AND status = 'PENDING'""")
        .param("reference", pspReference)
        .param("delayMillis", safetyNetDelay.toMillis())
        .param("id", id)
        .update();
  }

  /**
   * The call established nothing. The lease is kept, so the operation is tried again once it
   * expires; exponential backoff and needs_review arrive with #10.
   */
  public void recordError(UUID id, String error) {
    jdbc.sql("UPDATE psp_operations SET last_error = ?, updated_at = now() WHERE id = ?")
        .params(error.length() <= 1000 ? error : error.substring(0, 1000), id)
        .update();
  }
}
