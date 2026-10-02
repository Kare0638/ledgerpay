package com.ledgerpay.acceptance;

import com.ledgerpay.mockpsp.MockPspFaults;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The stack the acceptance tests run against (design §12): one PostgreSQL container with a database
 * per service, mock-psp started from its runnable jar in its own JVM under the dev profile (so
 * faults can be injected), and payment-service in the test JVM. Started once per test JVM.
 *
 * <p>Both services' ports are chosen up front, because each needs the other's address when it
 * starts: payment-service calls mock-psp, and mock-psp sends webhooks back.
 */
final class LiveStack {

  static final String WEBHOOK_SECRET = "acceptance-webhook-secret";

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  static final int PAYMENT_PORT;
  static final int MOCK_PSP_PORT;
  static final MockPspFaults FAULTS;
  private static final Process MOCK_PSP;

  static {
    POSTGRES.start();
    createDatabase("mockpsp");
    PAYMENT_PORT = freePort();
    MOCK_PSP_PORT = freePort();
    MOCK_PSP = startMockPsp();
    Runtime.getRuntime().addShutdownHook(new Thread(MOCK_PSP::destroy));
    awaitHealthy(mockPspUrl(), Duration.ofSeconds(90));
    FAULTS = new MockPspFaults(mockPspUrl());
  }

  private LiveStack() {}

  static String mockPspUrl() {
    return "http://localhost:" + MOCK_PSP_PORT;
  }

  static String paymentUrl() {
    return "http://localhost:" + PAYMENT_PORT;
  }

  static String jdbcUrl(String database) {
    return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
  }

  /**
   * payment-service, in this JVM, pointed at the stack. Its worker and inbox are driven by hand.
   */
  static void paymentServiceProperties(DynamicPropertyRegistry registry) {
    registry.add("server.port", () -> PAYMENT_PORT);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("ledgerpay.psp.base-url", LiveStack::mockPspUrl);
    registry.add("ledgerpay.psp.webhook-secret", () -> WEBHOOK_SECRET);
    registry.add("ledgerpay.psp.read-timeout", () -> "1s");
    registry.add("ledgerpay.psp.worker.enabled", () -> "false");
    registry.add("ledgerpay.inbox.processor.enabled", () -> "false");
  }

  private static Process startMockPsp() {
    Path jar = Path.of("target", "apps", "mock-psp.jar");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var builder =
        new ProcessBuilder(
                java,
                "-Xmx256m",
                // Held past payment-service's 1 s read timeout, but short enough for a test.
                "-Dmockpsp.faults.response-delay=3s",
                "-jar",
                jar.toString())
            .redirectErrorStream(true)
            .redirectOutput(new File("target/mock-psp.log"));
    var env = builder.environment();
    env.put("SERVER_PORT", String.valueOf(MOCK_PSP_PORT));
    env.put("DB_URL", jdbcUrl("mockpsp"));
    env.put("DB_USERNAME", POSTGRES.getUsername());
    env.put("DB_PASSWORD", POSTGRES.getPassword());
    env.put("SPRING_PROFILES_ACTIVE", "dev");
    env.put("PSP_WEBHOOK_URL", paymentUrl() + "/webhooks/psp");
    env.put("PSP_WEBHOOK_SECRET", WEBHOOK_SECRET);
    env.put("PSP_SETTLE_DELAY", "0ms");
    try {
      return builder.start();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot start mock-psp from " + jar.toAbsolutePath(), e);
    }
  }

  private static void createDatabase(String name) {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int freePort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void awaitHealthy(String baseUrl, Duration timeout) {
    HttpClient http = HttpClient.newHttpClient();
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
      if (!MOCK_PSP.isAlive()) {
        throw new IllegalStateException("mock-psp exited; see target/mock-psp.log");
      }
      try {
        var response =
            http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 200) {
          return;
        }
      } catch (IOException e) {
        // not listening yet
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalStateException(
        "mock-psp not healthy in " + timeout + "; see target/mock-psp.log");
  }
}
