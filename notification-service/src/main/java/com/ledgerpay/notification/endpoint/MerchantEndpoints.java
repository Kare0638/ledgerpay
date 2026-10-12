package com.ledgerpay.notification.endpoint;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantEndpoints {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final JdbcClient jdbc;

  public MerchantEndpoints(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<MerchantEndpoint> find(String merchantId) {
    return jdbc.sql(
            "SELECT merchant_id, webhook_url, signing_secret FROM merchant_endpoints"
                + " WHERE merchant_id = ?")
        .param(merchantId)
        .query(
            (rs, row) ->
                new MerchantEndpoint(
                    rs.getString("merchant_id"),
                    URI.create(rs.getString("webhook_url")),
                    rs.getString("signing_secret")))
        .optional();
  }

  /**
   * Sets the merchant's webhook URL with a new signing secret: registering again rotates the
   * secret. Webhooks already being retried are signed with whichever secret is current when they
   * are sent.
   */
  public MerchantEndpoint register(String merchantId, URI webhookUrl) {
    String secret = "whsec_" + HexFormat.of().formatHex(randomBytes(32));
    jdbc.sql(
            """
            INSERT INTO merchant_endpoints (merchant_id, webhook_url, signing_secret)
            VALUES (:merchant, :url, :secret)
            ON CONFLICT (merchant_id) DO UPDATE
               SET webhook_url = EXCLUDED.webhook_url,
                   signing_secret = EXCLUDED.signing_secret,
                   updated_at = now()""")
        .param("merchant", merchantId)
        .param("url", webhookUrl.toString())
        .param("secret", secret)
        .update();
    return new MerchantEndpoint(merchantId, webhookUrl, secret);
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
