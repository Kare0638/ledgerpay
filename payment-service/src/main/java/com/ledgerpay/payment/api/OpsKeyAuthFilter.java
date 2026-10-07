package com.ledgerpay.payment.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /ops/**} with a separate operations key (design §7): {@code Authorization:
 * Bearer <ops-key>}. Only the key's SHA-256 is configured, and a merchant key never passes. With no
 * hash configured, every ops request is 401: the endpoints are off.
 */
public class OpsKeyAuthFilter extends OncePerRequestFilter {

  private final byte[] expectedHash;
  private final ProblemWriter problems;

  /**
   * @param apiKeyHash hex SHA-256 of the ops key, as {@link ApiKeys#hash} computes it; blank turns
   *     the ops endpoints off
   */
  public OpsKeyAuthFilter(String apiKeyHash, ProblemWriter problems) {
    this.expectedHash =
        apiKeyHash == null || apiKeyHash.isBlank()
            ? null
            : apiKeyHash.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    this.problems = problems;
    if (expectedHash == null) {
      logger.warn(
          "No ops key hash configured (ledgerpay.ops.api-key-hash): /ops endpoints are off");
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean valid =
        expectedHash != null
            && ApiKeyAuthFilter.bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION))
                .map(key -> ApiKeys.hash(key).getBytes(StandardCharsets.US_ASCII))
                // Constant time, so response timing does not reveal how much of the hash matched.
                .map(hash -> MessageDigest.isEqual(hash, expectedHash))
                .orElse(false);
    if (!valid) {
      problems.write(response, ErrorCode.UNAUTHENTICATED, "Missing or invalid ops key");
      return;
    }
    chain.doFilter(request, response);
  }
}
