package com.ledgerpay.benchmarks;

import com.ledgerpay.payment.psp.PspClient;
import com.ledgerpay.payment.psp.PspOperationWorker;
import com.ledgerpay.payment.psp.PspOperations;
import com.ledgerpay.payment.psp.PspProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One {@link PspOperationWorker#runOnce()} per operation: claim a batch of pending operations, make
 * every PSP call on the executor under test, record each acceptance through a bounded HikariCP pool
 * (design §14.1). The worker, PSP client and SQL are payment-service's own; only the executor, the
 * PSP's response delay, the batch size and the pool size vary.
 *
 * <p>The PSP is {@link StubPspServer}, in its own process so that its threads never show up in this
 * JVM's thread counts. PostgreSQL comes from {@code -Dledgerpay.bench.jdbc-url} if set (CI uses a
 * service container), otherwise from Testcontainers.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class PspDispatchBenchmark {

  @Param({"virtual", "fixed-10", "fixed-50", "fixed-200"})
  public String executor;

  @Param({"0", "20", "200"})
  public int pspDelayMillis;

  @Param({"20", "200"})
  public int batchSize;

  @Param({"10"})
  public int poolSize;

  /**
   * The PSP client's HTTP executor: {@code virtual} as in production, or {@code spring-default},
   * the fallback that starts a platform thread per request, which JFR found before the fix.
   */
  @Param({"virtual"})
  public String httpExecutor;

  private PostgreSQLContainer<?> container;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;
  private Process psp;
  private ExecutorService calls;
  private PspOperationWorker worker;

  @Setup(Level.Trial)
  public void start() throws IOException {
    String url = System.getProperty("ledgerpay.bench.jdbc-url");
    String user = System.getProperty("ledgerpay.bench.jdbc-user", "ledgerpay");
    String password = System.getProperty("ledgerpay.bench.jdbc-password", "ledgerpay");
    if (url == null) {
      container = new PostgreSQLContainer<>("postgres:16-alpine");
      container.start();
      url = container.getJdbcUrl();
      user = container.getUsername();
      password = container.getPassword();
    }
    Flyway.configure().dataSource(url, user, password).load().migrate();

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setMaximumPoolSize(poolSize);
    config.setMinimumIdle(poolSize);
    dataSource = new HikariDataSource(config);
    jdbc = JdbcClient.create(dataSource);
    jdbc.sql(
            """
            INSERT INTO merchants (id, api_key_hash) VALUES ('m_bench', 'bench')
            ON CONFLICT (id) DO NOTHING""")
        .update();

    psp = startStubPsp(pspDelayMillis);
    int port;
    try (var out =
        new BufferedReader(new InputStreamReader(psp.getInputStream(), StandardCharsets.UTF_8))) {
      port = Integer.parseInt(out.readLine().strip());
    }

    var properties =
        new PspProperties(
            URI.create("http://127.0.0.1:" + port),
            Duration.ofSeconds(2),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            batchSize,
            Duration.ofSeconds(1),
            Duration.ofMinutes(15),
            "mock-psp",
            "unused");
    calls = executor(executor);
    worker =
        new PspOperationWorker(
            new PspOperations(jdbc),
            new PspClient(
                properties,
                RestClient.builder(),
                httpExecutor.equals("virtual")
                    ? Executors.newVirtualThreadPerTaskExecutor()
                    : null),
            (operation, outcome) -> {
              throw new IllegalStateException("The stub never returns a final outcome");
            },
            properties,
            calls);
  }

  static ExecutorService executor(String name) {
    if (name.equals("virtual")) {
      return Executors.newVirtualThreadPerTaskExecutor();
    }
    if (name.startsWith("fixed-")) {
      return Executors.newFixedThreadPool(Integer.parseInt(name.substring("fixed-".length())));
    }
    throw new IllegalArgumentException("Unknown executor " + name);
  }

  /** Keeps the tables small, so later iterations claim from the same amount of data. */
  @Setup(Level.Iteration)
  public void clear() {
    // DELETE, not TRUNCATE: a cascading TRUNCATE reaches the append-only ledger tables, whose
    // triggers reject it. The benchmark never books journals, so nothing references these rows.
    jdbc.sql("DELETE FROM psp_operations").update();
    jdbc.sql("DELETE FROM payments").update();
  }

  /**
   * A fresh batch of due operations for the next call; not part of the measured time. Due an hour
   * ago, so that a clock step between seeding and claiming cannot make them not yet due.
   */
  @Setup(Level.Invocation)
  public void seed() {
    jdbc.sql(
            """
            WITH created AS (
                INSERT INTO payments (id, merchant_id, merchant_reference, amount_minor, currency, status)
                SELECT gen_random_uuid(), 'm_bench', 'ref_' || gen_random_uuid(), 1000, 'GBP', 'AUTH_PENDING'
                  FROM generate_series(1, :n)
                RETURNING id)
            INSERT INTO psp_operations
                (id, payment_id, type, psp_request_id, amount_minor, currency, status, next_attempt_at)
            SELECT op, id, 'AUTHORIZE', 'req_' || op, 1000, 'GBP', 'PENDING', now() - interval '1 hour'
              FROM (SELECT gen_random_uuid() AS op, id FROM created) seeded""")
        .param("n", batchSize)
        .update();
  }

  @Benchmark
  public int dispatchBatch() {
    int claimed = worker.runOnce();
    if (claimed != batchSize) {
      throw new IllegalStateException("Claimed " + claimed + " of " + batchSize);
    }
    return claimed;
  }

  @TearDown(Level.Trial)
  public void stop() {
    calls.close();
    psp.destroy();
    dataSource.close();
    if (container != null) {
      container.stop();
    }
  }

  /** Starts {@link StubPspServer} in its own JVM, from the same classpath, with a small heap. */
  private static Process startStubPsp(int delayMillis) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return new ProcessBuilder(
            java,
            "-Xmx256m",
            // One write for headers and body is not guaranteed; without TCP_NODELAY, Nagle and
            // delayed ACKs add about 40 ms to responses, which would swamp the PSP delay.
            "-Dsun.net.httpserver.nodelay=true",
            "-cp",
            System.getProperty("java.class.path"),
            StubPspServer.class.getName(),
            String.valueOf(delayMillis))
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start();
  }
}
