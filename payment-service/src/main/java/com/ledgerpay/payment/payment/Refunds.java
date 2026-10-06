package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class Refunds {

  // The currency is the payment's: a refund is always in the currency it refunds.
  private static final String SELECT =
      """
      SELECT r.id, r.payment_id, r.merchant_id, r.merchant_reference, r.amount_minor, p.currency,
             r.status, r.reason
        FROM refunds r JOIN payments p ON p.id = r.payment_id""";

  private final JdbcClient jdbc;

  public Refunds(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a PENDING refund unless the merchant reference is taken; {@code ON CONFLICT DO NOTHING}
   * keeps the transaction usable when it is.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean insertIfReferenceFree(
      UUID id,
      UUID paymentId,
      String merchantId,
      String merchantReference,
      Money amount,
      String reason) {
    return jdbc.sql(
                """
                INSERT INTO refunds
                    (id, payment_id, merchant_id, merchant_reference, amount_minor, status, reason)
                VALUES (:id, :payment, :merchant, :reference, :amount, 'PENDING', :reason)
                ON CONFLICT (merchant_id, merchant_reference) DO NOTHING""")
            .param("id", id)
            .param("payment", paymentId)
            .param("merchant", merchantId)
            .param("reference", merchantReference)
            .param("amount", amount.minor())
            .param("reason", reason)
            .update()
        == 1;
  }

  /** Scoped to the merchant: another merchant's refund is simply not found. */
  public Optional<Refund> find(String merchantId, UUID id) {
    return jdbc.sql(SELECT + " WHERE r.merchant_id = ? AND r.id = ?")
        .params(merchantId, id)
        .query(Refunds::map)
        .optional();
  }

  public Optional<Refund> findByReference(String merchantId, String merchantReference) {
    return jdbc.sql(SELECT + " WHERE r.merchant_id = ? AND r.merchant_reference = ?")
        .params(merchantId, merchantReference)
        .query(Refunds::map)
        .optional();
  }

  /** Records the PSP's final answer; the caller holds the payment lock. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(UUID id, RefundStatus status) {
    jdbc.sql(
            """
            UPDATE refunds SET status = :status, updated_at = now()
             WHERE id = :id AND status = 'PENDING'""")
        .param("status", status.name())
        .param("id", id)
        .update();
  }

  private static Refund map(ResultSet rs, int row) throws SQLException {
    return new Refund(
        rs.getObject("id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getString("merchant_id"),
        rs.getString("merchant_reference"),
        Money.of(rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency"))),
        RefundStatus.valueOf(rs.getString("status")),
        rs.getString("reason"));
  }
}
