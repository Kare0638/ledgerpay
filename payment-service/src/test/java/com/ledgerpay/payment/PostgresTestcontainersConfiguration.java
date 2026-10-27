package com.ledgerpay.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

  /** Postgres 16 with pgvector, which the assistant's precedents need (V13, #64). */
  public static final DockerImageName IMAGE =
      DockerImageName.parse("pgvector/pgvector:0.8.0-pg16").asCompatibleSubstituteFor("postgres");

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(IMAGE);
  }
}
