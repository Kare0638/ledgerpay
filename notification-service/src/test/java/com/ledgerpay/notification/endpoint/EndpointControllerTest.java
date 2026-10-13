package com.ledgerpay.notification.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerpay.notification.PostgresTestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // SHA-256 of "test-ops-key"
    properties =
        "ledgerpay.ops.api-key-hash=24e29f6f9b06174f48d833c5cd2db2966eea8e5edacb80e365e81388e8277fdc")
@Import(PostgresTestcontainersConfiguration.class)
class EndpointControllerTest {

  static final String OPS_KEY = "test-ops-key";

  @Autowired TestRestTemplate rest;
  @Autowired MerchantEndpoints endpoints;

  ResponseEntity<Map> register(String merchant, String url, String key) {
    HttpHeaders headers = new HttpHeaders();
    if (key != null) {
      headers.setBearerAuth(key);
    }
    return rest.exchange(
        "/ops/merchants/{merchant}/webhook-endpoint",
        HttpMethod.PUT,
        new HttpEntity<>(Map.of("url", url), headers),
        Map.class,
        merchant);
  }

  @Test
  void registersTheEndpointAndShowsTheSecretOnce() {
    String merchant = "m_" + UUID.randomUUID();

    var response = register(merchant, "https://merchant.example/hooks", OPS_KEY);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .containsEntry("merchantId", merchant)
        .containsEntry("url", "https://merchant.example/hooks");
    String secret = (String) response.getBody().get("signingSecret");
    assertThat(secret).startsWith("whsec_").hasSize("whsec_".length() + 64);
    assertThat(endpoints.find(merchant).orElseThrow().signingSecret()).isEqualTo(secret);
  }

  @Test
  void registeringAgainRotatesTheSecret() {
    String merchant = "m_" + UUID.randomUUID();
    String first =
        (String) register(merchant, "https://a.example/h", OPS_KEY).getBody().get("signingSecret");

    var second = register(merchant, "https://b.example/h", OPS_KEY);

    assertThat(second.getBody().get("signingSecret")).isNotEqualTo(first);
    var stored = endpoints.find(merchant).orElseThrow();
    assertThat(stored.webhookUrl()).hasToString("https://b.example/h");
    assertThat(stored.signingSecret()).isEqualTo(second.getBody().get("signingSecret"));
  }

  @Test
  void needsTheOpsKey() {
    String merchant = "m_" + UUID.randomUUID();

    assertThat(register(merchant, "https://x.example/h", null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(register(merchant, "https://x.example/h", "wrong").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(endpoints.find(merchant)).isEmpty();
  }

  @Test
  void refusesAUrlThatIsNotHttp() {
    assertThat(register("m_" + UUID.randomUUID(), "ftp://x.example", OPS_KEY).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  /** They match {@code https?://\S+} but are no usable URL: a 400, not a 500. */
  @ParameterizedTest
  @ValueSource(strings = {"http://exa^mple.com", "http://a|b.example", "http://:80/hooks"})
  void refusesAUrlThatIsNotAValidUriWith400(String url) {
    String merchant = "m_" + UUID.randomUUID();

    assertThat(register(merchant, url, OPS_KEY).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(endpoints.find(merchant)).isEmpty();
  }
}
