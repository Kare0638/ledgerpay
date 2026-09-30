package com.ledgerpay.payment.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * API keys are stored as SHA-256 hashes. A plain hash is enough because keys are long random
 * strings, not user-chosen passwords, so there is nothing for a slow hash to protect.
 */
public final class ApiKeys {

  private ApiKeys() {}

  public static String hash(String apiKey) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", e);
    }
  }
}
