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
public class Payments {

  private static final String COLUMNS =
      """
      id, merchant_id, merchant_reference, amount_minor, currency, status,
      captured_minor, refunded_minor, refund_reserved_minor""";

  private final JdbcClient jdbc;

  public Payments(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a new AUTH_PENDING payment unless the merchant reference is taken. {@code ON CONFLICT
   * DO NOTHING} keeps the transaction usable when it is: a plain unique violation would abort it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean insertIfReferenceFree(
      UUID id, String merchantId, String merchantReference, Money amount) {
    return jdbc.sql(
                """
                INSERT INTO payments (id, merchant_id, merchant_reference, amount_minor, currency, status)
                VALUES (:id, :merchant, :reference, :amount, :currency, :status)
                ON CONFLICT (merchant_id, merchant_reference) DO NOTHING""")
            .param("id", id)
            .param("merchant", merchantId)
            .param("reference", merchantReference)
            .param("amount", amount.minor())
            .param("currency", amount.currency().getCurrencyCode())
            .param("status", PaymentStatus.AUTH_PENDING.name())
            .update()
        == 1;
  }

  /** Scoped to the merchant: another merchant's payment is simply not found. */
  public Optional<Payment> find(String merchantId, UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE merchant_id = ? AND id = ?")
        .params(merchantId, id)
        .query(Payments::map)
        .optional();
  }

  public Optional<Payment> findByReference(String merchantId, String merchantReference) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM payments WHERE merchant_id = ? AND merchant_reference = ?")
        .params(merchantId, merchantReference)
        .query(Payments::map)
        .optional();
  }

  /** Locks the payment for a money decision; payments are always locked before operations. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Payment> lock(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE id = ? FOR UPDATE")
        .param(id)
        .query(Payments::map)
        .optional();
  }

  /** As {@link #lock(UUID)}, scoped to the merchant: another merchant's payment is not found. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Payment> lock(String merchantId, UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM payments WHERE merchant_id = ? AND id = ? FOR UPDATE")
        .params(merchantId, id)
        .query(Payments::map)
        .optional();
  }

  /** Writes a new status and captured amount for a payment the caller has locked. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void update(UUID id, PaymentStatus status, Money captured) {
    jdbc.sql(
            """
            UPDATE payments
               SET status = :status, captured_minor = :captured,
                   version = version + 1, updated_at = now()
             WHERE id = :id""")
        .param("status", status.name())
        .param("captured", captured.minor())
        .param("id", id)
        .update();
  }

  /** Holds {@code amount} of the refundable balance for a pending refund (design §5.4). */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reserveRefund(UUID id, Money amount) {
    jdbc.sql(
            """
            UPDATE payments
               SET refund_reserved_minor = refund_reserved_minor + :amount,
                   version = version + 1, updated_at = now()
             WHERE id = :id""")
        .param("amount", amount.minor())
        .param("id", id)
        .update();
  }

  /**
   * Releases a refund's reservation once the PSP has answered: into {@code refunded_minor} if it
   * succeeded, back to the refundable balance if it failed.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void settleRefund(UUID id, Money amount, boolean succeeded) {
    jdbc.sql(
            """
            UPDATE payments
               SET refund_reserved_minor = refund_reserved_minor - :amount,
                   refunded_minor = refunded_minor + :refunded,
                   version = version + 1, updated_at = now()
             WHERE id = :id""")
        .param("amount", amount.minor())
        .param("refunded", succeeded ? amount.minor() : 0)
        .param("id", id)
        .update();
  }

  public int feeBps(String merchantId) {
    return jdbc.sql("SELECT fee_bps FROM merchants WHERE id = ?")
        .param(merchantId)
        .query(Integer.class)
        .single();
  }

  private static Payment map(ResultSet rs, int row) throws SQLException {
    Currency currency = Currency.getInstance(rs.getString("currency"));
    return new Payment(
        rs.getObject("id", UUID.class),
        rs.getString("merchant_id"),
        rs.getString("merchant_reference"),
        Money.of(rs.getLong("amount_minor"), currency),
        PaymentStatus.valueOf(rs.getString("status")),
        Money.of(rs.getLong("captured_minor"), currency),
        Money.of(rs.getLong("refunded_minor"), currency),
        Money.of(rs.getLong("refund_reserved_minor"), currency));
  }
}
