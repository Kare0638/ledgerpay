package com.ledgerpay.mockpsp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfiguration.class)
class MockPspApplicationTests {

  @Autowired TestRestTemplate rest;
  @Autowired JdbcClient jdbc;

  @Test
  void healthIsUp() {
    var response = rest.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  void flywayAppliedBaseline() {
    var versions =
        jdbc.sql("SELECT version FROM flyway_schema_history WHERE success")
            .query(String.class)
            .list();

    assertThat(versions).contains("0");
  }
}
