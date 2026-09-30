package com.ledgerpay.payment.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates merchant API calls with {@code Authorization: Bearer <api-key>} and exposes the
 * merchant as a request attribute. Anything else gets 401 {@code UNAUTHENTICATED}.
 *
 * <p>Filters run before the DispatcherServlet, so controller advice never sees their exceptions: a
 * database outage during the lookup is turned into the same 503 here.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  private static final String BEARER = "Bearer ";

  private final MerchantLookup merchants;
  private final ProblemWriter problems;

  public ApiKeyAuthFilter(MerchantLookup merchants, ProblemWriter problems) {
    this.merchants = merchants;
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<AuthenticatedMerchant> merchant;
    try {
      merchant =
          apiKey(request.getHeader(HttpHeaders.AUTHORIZATION))
              .flatMap(key -> merchants.byApiKeyHash(ApiKeys.hash(key)));
    } catch (DataAccessException e) {
      if (DatabaseFailures.isTemporary(e)) {
        logger.warn("Database temporarily unavailable during authentication", e);
        problems.writeTemporarilyUnavailable(response);
      } else {
        logger.error("Database error during authentication", e);
        problems.write(response, ErrorCode.INTERNAL_ERROR, "Unexpected error");
      }
      return;
    }
    if (merchant.isEmpty()) {
      problems.write(response, ErrorCode.UNAUTHENTICATED, "Missing or invalid API key");
      return;
    }
    request.setAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE, merchant.get());
    chain.doFilter(request, response);
  }

  private static Optional<String> apiKey(String authorization) {
    // The scheme name is case-insensitive (RFC 9110 §11.1): "bearer" and "BEARER" are fine too.
    if (authorization == null
        || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return Optional.empty();
    }
    String key = authorization.substring(BEARER.length()).strip();
    return key.isEmpty() ? Optional.empty() : Optional.of(key);
  }
}
