package com.ledgerpay.mockpsp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockPspApplicationTests.Containers.class)
class MockPspApplicationTests {

  @TestConfiguration(proxyBeanMethods = false)
  static class Containers {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
      return new PostgreSQLContainer<>("postgres:16-alpine");
    }
  }

  @Autowired TestRestTemplate http;
  @Autowired JdbcClient jdbc;

  @Test
  void flywayAppliesBaselineMigration() {
    Integer applied =
        jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
            .query(Integer.class)
            .single();
    assertThat(applied).isEqualTo(1);
  }

  @Test
  void healthReportsUp() {
    String body = http.getForObject("/actuator/health", String.class);
    assertThat(body).contains("\"status\":\"UP\"");
  }
}
