package com.ledgerpay.payment.api;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMerchantLookup implements MerchantLookup {

  private final JdbcClient jdbc;

  public JdbcMerchantLookup(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AuthenticatedMerchant> byApiKeyHash(String apiKeyHash) {
    return jdbc.sql("SELECT id FROM merchants WHERE api_key_hash = ?")
        .param(apiKeyHash)
        .query(String.class)
        .optional()
        .map(AuthenticatedMerchant::new);
  }
}
