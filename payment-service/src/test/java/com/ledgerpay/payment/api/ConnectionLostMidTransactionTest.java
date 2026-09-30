package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import com.ledgerpay.payment.idempotency.IdempotentExecutor;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connection dies after the idempotent transaction has begun: whatever the transaction manager
 * throws on the way out, the client must get 503 and retry with the same key, because the outcome
 * is unknown.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
  PostgresTestcontainersConfiguration.class,
  ConnectionLostMidTransactionTest.KillerController.class
})
class ConnectionLostMidTransactionTest {

  /** Runs the idempotent path and has PostgreSQL terminate the connection halfway through. */
  @TestConfiguration(proxyBeanMethods = false)
  @RestController
  static class KillerController {
    private final IdempotentExecutor idempotency;
    private final JdbcClient jdbc;

    KillerController(IdempotentExecutor idempotency, JdbcClient jdbc) {
      this.idempotency = idempotency;
      this.jdbc = jdbc;
    }

    @PostMapping("/v1/test/connection-lost")
    void connectionLost(
        @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant) {
      idempotency.execute(
          merchant.id(),
          "test",
          UUID.randomUUID().toString(),
          "request",
          () -> {
            jdbc.sql("SELECT pg_terminate_backend(pg_backend_pid())").query().singleValue();
            return new IdempotentExecutor.Result(HttpStatus.ACCEPTED.value(), "unreachable");
          });
    }
  }

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;

  @Test
  void aConnectionLostMidTransactionIs503() throws Exception {
    String merchantId = "m_" + UUID.randomUUID();
    String apiKey = "mk_test_" + UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, api_key_hash) VALUES (?, ?)")
        .params(merchantId, ApiKeys.hash(apiKey))
        .update();
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(apiKey);

    ResponseEntity<String> response =
        rest.exchange(
            "/v1/test/connection-lost", HttpMethod.POST, new HttpEntity<>(headers), String.class);

    JsonNode body = json.readTree(response.getBody());
    assertThat(body.get("code").asText()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
  }
}
