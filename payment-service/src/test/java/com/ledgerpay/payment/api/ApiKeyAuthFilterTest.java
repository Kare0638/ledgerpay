package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyAuthFilterTest {

  static final String KEY = "mk_test_valid";

  // Built like Spring's: it registers the mixin that puts ProblemDetail properties at the top
  // level.
  final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();
  final ProblemWriter problems = new ProblemWriter(json);

  @BeforeEach
  void traceId() {
    MDC.put(TraceIds.MDC_KEY, "trace-1");
  }

  @AfterEach
  void clearTraceId() {
    MDC.remove(TraceIds.MDC_KEY);
  }

  MerchantLookup knowsOnly(String key) {
    return hash ->
        hash.equals(ApiKeys.hash(key))
            ? Optional.of(new AuthenticatedMerchant("m_1"))
            : Optional.empty();
  }

  /** Runs the filter; returns the merchant the chain saw, or null if the chain was not reached. */
  AuthenticatedMerchant run(
      MerchantLookup lookup, String authorization, MockHttpServletResponse response)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/payments");
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    AtomicReference<AuthenticatedMerchant> seen = new AtomicReference<>();
    new ApiKeyAuthFilter(lookup, problems)
        .doFilter(
            request,
            response,
            new MockFilterChain() {
              @Override
              public void doFilter(
                  jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(
                    (AuthenticatedMerchant)
                        req.getAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE));
              }
            });
    return seen.get();
  }

  JsonNode problem(MockHttpServletResponse response) throws Exception {
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    JsonNode body = json.readTree(response.getContentAsString());
    assertThat(body.get("traceId").asText()).isEqualTo("trace-1");
    return body;
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer ", "bearer ", "BEARER ", "bEaReR "})
  void theSchemeNameIsCaseInsensitive(String scheme) throws Exception {
    var response = new MockHttpServletResponse();

    assertThat(run(knowsOnly(KEY), scheme + KEY, response))
        .isEqualTo(new AuthenticatedMerchant("m_1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "Bearer", "Bearer   ", "Basic " + KEY, "Bearer mk_test_unknown", KEY})
  void anythingElseIs401(String authorization) throws Exception {
    var response = new MockHttpServletResponse();

    assertThat(run(knowsOnly(KEY), authorization, response)).isNull();
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(problem(response).get("code").asText()).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void aTemporaryDatabaseFailureIs503WithRetryAfter() throws Exception {
    var response = new MockHttpServletResponse();
    MerchantLookup timingOut =
        hash -> {
          throw new QueryTimeoutException("statement timeout");
        };

    assertThat(run(timingOut, "Bearer " + KEY, response)).isNull();
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    assertThat(problem(response).get("code").asText()).isEqualTo("TEMPORARILY_UNAVAILABLE");
  }

  @Test
  void anyOtherDatabaseFailureIs500ProblemDetailsNotTheDefaultErrorPage() throws Exception {
    var response = new MockHttpServletResponse();
    MerchantLookup broken =
        hash -> {
          throw new BadSqlGrammarException(
              "lookup", "SELECT", new java.sql.SQLException("syntax error"));
        };

    assertThat(run(broken, "Bearer " + KEY, response)).isNull();
    assertThat(response.getStatus()).isEqualTo(500);
    JsonNode body = problem(response);
    assertThat(body.get("code").asText()).isEqualTo("INTERNAL_ERROR");
    assertThat(body.get("detail").asText()).isEqualTo("Unexpected error");
  }
}
