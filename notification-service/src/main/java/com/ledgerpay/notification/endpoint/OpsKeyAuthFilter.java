package com.ledgerpay.notification.endpoint;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /ops/**} as payment-service does (design §7): {@code Authorization: Bearer
 * <ops-key>}, compared by SHA-256 in constant time. With no hash configured the endpoints are off.
 */
class OpsKeyAuthFilter extends OncePerRequestFilter {

  private static final String BEARER = "Bearer ";

  private final byte[] expectedHash;

  OpsKeyAuthFilter(String apiKeyHash) {
    this.expectedHash =
        apiKeyHash == null || apiKeyHash.isBlank()
            ? null
            : apiKeyHash.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    if (expectedHash == null) {
      logger.warn(
          "No ops key hash configured (ledgerpay.ops.api-key-hash): /ops endpoints are off");
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    boolean valid =
        expectedHash != null
            && header != null
            && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
            && MessageDigest.isEqual(
                sha256(header.substring(BEARER.length()).strip()), expectedHash);
    if (!valid) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response
          .getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                  + "\"detail\":\"Missing or invalid ops key\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private static byte[] sha256(String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).getBytes(StandardCharsets.US_ASCII);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
