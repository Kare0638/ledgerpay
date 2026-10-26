package com.ledgerpay.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration.class)
class NotificationServiceApplicationTests {

  @Autowired TestRestTemplate rest;

  @Test
  void healthIsUp() {
    var response = rest.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
    // A status for probes, no details: they name the database and broker it reaches.
    assertThat(response.getBody()).doesNotContain("components", "details");
  }

  /**
   * Actuator endpoints that print configuration, the environment or memory would show any key the
   * service runs with, GEMINI_API_KEY among them. None is exposed, with or without credentials.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "env",
        "env/GEMINI_API_KEY",
        "configprops",
        "beans",
        "heapdump",
        "threaddump",
        "loggers",
        "mappings"
      })
  void endpointsThatCouldShowSecretsAreNotExposed(String endpoint) {
    assertThat(rest.getForEntity("/actuator/" + endpoint, String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }
}
