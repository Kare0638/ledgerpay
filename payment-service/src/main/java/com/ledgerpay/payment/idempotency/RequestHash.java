package com.ledgerpay.payment.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the canonical form of a parsed request: properties sorted, no whitespace, unknown
 * fields already dropped by parsing. Two bodies that mean the same request hash the same, however
 * they are formatted.
 */
public final class RequestHash {

  private static final ObjectMapper CANONICAL =
      JsonMapper.builder()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  private RequestHash() {}

  public static String of(Object request) {
    try {
      byte[] canonical = CANONICAL.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("cannot hash request", e);
    }
  }
}
