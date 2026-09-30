package com.ledgerpay.payment.psp;

import com.ledgerpay.common.money.Money;
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
}
