package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.idempotency.IdempotentExecutor;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * With the database really down, every path answers 503 {@code TEMPORARILY_UNAVAILABLE} with {@code
 * Retry-After}, so clients retry with the same key instead of treating it as a failure.
 *
 * <p>Uses its own container, started for the context and stopped before each test, and short pool
 * timeouts so a lost database fails in about a second.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.hikari.connection-timeout=1000",
      "spring.datasource.hikari.validation-timeout=250"
    })
@Testcontainers
class DatabaseOutageIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  /**
   * Looks merchants up in the database, unless told to accept any key: that lets a request get past
   * the filter while the database is down, to reach the transactional path behind it.
   */
  static class SwitchableMerchantLookup implements MerchantLookup {
    private final MerchantLookup database;
    volatile boolean acceptAnyKey;

    SwitchableMerchantLookup(MerchantLookup database) {
      this.database = database;
    }

    @Override
    public Optional<AuthenticatedMerchant> byApiKeyHash(String apiKeyHash) {
      return acceptAnyKey
          ? Optional.of(new AuthenticatedMerchant("m_outage"))
          : database.byApiKeyHash(apiKeyHash);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @Primary
    SwitchableMerchantLookup switchableMerchantLookup(JdbcMerchantLookup database) {
      return new SwitchableMerchantLookup(database);
    }
  }

  @Autowired TestRestTemplate rest;
  @Autowired ObjectMapper json;
  @Autowired SwitchableMerchantLookup merchants;
  @Autowired IdempotentExecutor idempotency;

  @BeforeEach
  void databaseGoesDown() {
    if (POSTGRES.isRunning()) {
      POSTGRES.stop();
    }
    merchants.acceptAnyKey = false;
  }

  ResponseEntity<String> exchange(HttpMethod method, String path, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("mk_test_any");
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "key-1");
    return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  ResponseEntity<String> createPayment() {
    return exchange(
        HttpMethod.POST,
        "/v1/payments",
        """
        {"merchantReference": "order-1", "amountMinor": 100, "currency": "GBP"}""");
  }

  void assertTemporarilyUnavailable(ResponseEntity<String> response) throws Exception {
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    JsonNode body = json.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    assertThat(body.get("retryAfterSeconds").asInt()).isEqualTo(5);
    assertThat(body.get("traceId").asText())
        .isNotBlank()
        .isEqualTo(response.getHeaders().getFirst("X-Trace-Id"));
  }

  @Test
  void theAuthenticationFilterAnswers503() throws Exception {
    assertTemporarilyUnavailable(createPayment());
  }

  @Test
  void aTransactionThatCannotBeginAnswers503() throws Exception {
    merchants.acceptAnyKey = true;

    // The transactional write path fails before any query, with a TransactionException rather
    // than a DataAccessException, which is why the handler lists both.
    assertThatThrownBy(
            () ->
                idempotency.execute(
                    "m_outage",
                    "scope",
                    "key",
                    "request",
                    () -> new IdempotentExecutor.Result(202, "unreachable")))
        .isInstanceOf(CannotCreateTransactionException.class);
    assertTemporarilyUnavailable(createPayment());
  }

  @Test
  void aQueryOutsideATransactionAnswers503() throws Exception {
    merchants.acceptAnyKey = true;

    assertTemporarilyUnavailable(
        exchange(HttpMethod.GET, "/v1/payments/" + UUID.randomUUID(), null));
  }
}
