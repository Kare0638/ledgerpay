package com.ledgerpay.payment.psp;

import com.ledgerpay.common.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
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
                        WHERE status = 'PENDING' AND NOT needs_review AND next_attempt_at <= now()
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
   * The call established nothing: keep the error, release the lease and try again after {@code
   * retryIn}, or set the operation aside for review. Either way its business state is left alone: a
   * reservation stays reserved and the payment stays pending, because the outcome is unknown.
   */
  public void recordFailure(UUID id, String error, Duration retryIn, boolean needsReview) {
    jdbc.sql(
            """
            UPDATE psp_operations
               SET last_error = :error, lease_until = NULL, needs_review = :review,
                   next_attempt_at = now() + :retryMillis * interval '1 millisecond',
                   updated_at = now()
             WHERE id = :id AND status = 'PENDING'""")
        .param("error", error.length() <= 1000 ? error : error.substring(0, 1000))
        .param("review", needsReview)
        .param("retryMillis", retryIn.toMillis())
        .param("id", id)
        .update();
  }

  /** An operation as the money transaction sees it. */
  public record OperationRow(
      UUID id,
      UUID paymentId,
      PspOperationType type,
      String pspRequestId,
      Money amount,
      String status,
      String pspReference,
      int resourceVersion) {

    public boolean isFinal() {
      return !status.equals("PENDING");
    }
  }

  /**
   * The payment an operation belongs to, read without a lock so the payment can be locked first.
   */
  public Optional<UUID> paymentIdOf(String pspRequestId) {
    return jdbc.sql("SELECT payment_id FROM psp_operations WHERE psp_request_id = ?")
        .param(pspRequestId)
        .query(UUID.class)
        .optional();
  }

  /** Locks the operation; the caller already holds the lock on its payment. */
  @Transactional(propagation = Propagation.MANDATORY)
  public OperationRow lock(String pspRequestId) {
    return jdbc.sql(
            """
            SELECT id, payment_id, type, psp_request_id, amount_minor, currency, status,
                   psp_reference, resource_version
              FROM psp_operations WHERE psp_request_id = ? FOR UPDATE""")
        .param(pspRequestId)
        .query(
            (rs, row) ->
                new OperationRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("payment_id", UUID.class),
                    PspOperationType.valueOf(rs.getString("type")),
                    rs.getString("psp_request_id"),
                    Money.of(
                        rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency"))),
                    rs.getString("status"),
                    rs.getString("psp_reference"),
                    rs.getInt("resource_version")))
        .single();
  }

  /**
   * Records the PSP's final answer on a locked operation. A rejection keeps its reason in {@code
   * last_error}, which raises an alert (design §5.3).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(
      UUID id,
      boolean succeeded,
      String pspReference,
      Instant succeededAt,
      int resourceVersion,
      String failureReason) {
    jdbc.sql(
            """
            UPDATE psp_operations
               SET status = :status, psp_reference = :reference, succeeded_at = :succeededAt,
                   resource_version = :version, last_error = :error,
                   lease_until = NULL, updated_at = now()
             WHERE id = :id""")
        .param("status", succeeded ? "SUCCEEDED" : "FAILED")
        .param("reference", pspReference)
        .param(
            "succeededAt", succeeded ? OffsetDateTime.ofInstant(succeededAt, ZoneOffset.UTC) : null)
        .param("version", resourceVersion)
        .param("error", succeeded ? null : "PSP rejected: " + failureReason)
        .param("id", id)
        .update();
  }
}
