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
