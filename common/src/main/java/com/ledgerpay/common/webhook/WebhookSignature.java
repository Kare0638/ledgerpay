package com.ledgerpay.common.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PSP webhook signatures (design §9.2): {@code X-PSP-Signature} is the lowercase hex HMAC-SHA256 of
 * {@code timestamp + "." + rawBody}, keyed with the shared secret, and {@code X-PSP-Timestamp} is
 * Unix epoch seconds. The raw body is signed as sent, so it must be verified before it is parsed.
 */
public final class WebhookSignature {

  public static final String TIMESTAMP_HEADER = "X-PSP-Timestamp";
  public static final String SIGNATURE_HEADER = "X-PSP-Signature";

  /** How far the timestamp may be from the receiver's clock, in either direction. */
  public static final Duration TOLERANCE = Duration.ofMinutes(5);

  private static final String ALGORITHM = "HmacSHA256";
  private static final HexFormat HEX = HexFormat.of();

  private WebhookSignature() {}

  public static String sign(byte[] secret, long timestamp, byte[] rawBody) {
    Mac mac = mac(secret);
    mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
    return HEX.formatHex(mac.doFinal(rawBody));
  }

  /**
   * True only if the timestamp is a number within {@link #TOLERANCE} of {@code now} and the
   * signature matches. The comparison takes the same time wherever the first difference is.
   */
  public static boolean verify(
      byte[] secret, String timestamp, byte[] rawBody, String signature, Instant now) {
    if (timestamp == null || signature == null) {
      return false;
    }
    long seconds;
    try {
      seconds = Long.parseLong(timestamp);
    } catch (NumberFormatException e) {
      return false;
    }
    if (Math.abs(now.getEpochSecond() - seconds) > TOLERANCE.toSeconds()) {
      return false;
    }
    byte[] expected = sign(secret, seconds, rawBody).getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII));
  }

  private static Mac mac(byte[] secret) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret, ALGORITHM));
      return mac;
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is mandatory on every JDK, and any non-empty key is valid for it.
      throw new IllegalStateException(e);
    }
  }
}
