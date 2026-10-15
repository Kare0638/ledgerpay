package com.ledgerpay.payment.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.PostgresTestcontainersConfiguration;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code /ops/settlement-reports} over HTTP and PostgreSQL (design §11.1): versions, duplicate
 * files, INVALID reports kept without rows, the size limit and concurrent uploads. Each test uses
 * its own PSP name, so reports from other tests never share its days or versions.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ledgerpay.ops.api-key-hash=" + SettlementReportIntegrationTest.OPS_KEY_HASH,
      "ledgerpay.settlement.max-file-size=64KB",
      "ledgerpay.psp.worker.enabled=false",
      "ledgerpay.inbox.processor.enabled=false"
    })
@Import(PostgresTestcontainersConfiguration.class)
class SettlementReportIntegrationTest {

  static final String OPS_KEY = "ops_test_key";
  static final String OPS_KEY_HASH =
      "17da52acda373b82d53e6601abffb233bc9a735579df11d2c78596cfa5890134";
  static final int MAX_FILE_SIZE = 64 * 1024;
  static final String DAY = "2026-10-13";
  static final String HEADER =
      "psp_reference,psp_request_id,txn_type,merchant_id,amount_minor,currency,succeeded_at\r\n";
  static final String CAPTURE = "psp_1,req_1,CAPTURE,m_1,10000,GBP,2026-10-13T10:00:00Z\r\n";
  static final String REFUND = "psp_2,req_2,REFUND,m_1,3000,GBP,2026-10-13T11:00:00.5Z\r\n";
  static final String BAD_AMOUNT = "psp_3,req_3,CAPTURE,m_1,30.00,GBP,2026-10-13T12:00:00Z\r\n";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper json;
  @Autowired SettlementReports reports;

  final HttpClient http = HttpClient.newHttpClient();
  String psp;

  @BeforeEach
  void newPsp() {
    psp = "test-" + UUID.randomUUID().toString().substring(0, 13);
  }

  record Response(int status, JsonNode body, String location) {}

  Response upload(String csv) throws Exception {
    return upload(csv.getBytes(StandardCharsets.UTF_8), "text/csv", OPS_KEY, false);
  }

  Response upload(byte[] body, String contentType, String key, boolean chunked) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:%d/ops/settlement-reports?businessDate=%s&psp=%s"
                        .formatted(port, DAY, psp)))
            .header("Content-Type", contentType)
            .POST(
                chunked
                    ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    if (key != null) {
      request.header("Authorization", "Bearer " + key);
    }
    return send(request.build());
  }

  Response get(String pathAndQuery) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
            .header("Authorization", "Bearer " + OPS_KEY)
            .build());
  }

  Response send(HttpRequest request) throws Exception {
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    String body = response.body();
    return new Response(
        response.statusCode(),
        json.readTree(body == null || body.isEmpty() ? "{}" : body),
        response.headers().firstValue("Location").orElse(null));
  }

  long reportCount() {
    return jdbc.sql("SELECT count(*) FROM settlement_reports WHERE psp = ?")
        .param(psp)
        .query(Long.class)
        .single();
  }

  long rowCount(String reportId) {
    return jdbc.sql("SELECT count(*) FROM settlement_rows WHERE report_id = ?::uuid")
        .param(reportId)
        .query(Long.class)
        .single();
  }

  @Test
  void aValidReportIsStoredWithEveryRowAndItsLine() throws Exception {
    Response response = upload(HEADER + CAPTURE + REFUND);

    assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
    JsonNode report = response.body();
    String id = report.path("reportId").asText();
    assertThat(response.location()).isEqualTo("/ops/settlement-reports/" + id);
    assertThat(report.path("status").asText()).isEqualTo("VALID");
    assertThat(report.path("version").asInt()).isEqualTo(1);
    assertThat(report.path("rowCount").asInt()).isEqualTo(2);
    assertThat(report.path("businessDate").asText()).isEqualTo(DAY);
    assertThat(report.path("fileSha256").asText())
        .isEqualTo(
            SettlementImporter.sha256(
                (HEADER + CAPTURE + REFUND).getBytes(StandardCharsets.UTF_8)));

    assertThat(reports.rows(UUID.fromString(id)))
        .containsExactly(
            new SettlementRow(
                2,
                "psp_1",
                "req_1",
                "CAPTURE",
                "m_1",
                10000,
                "GBP",
                Instant.parse("2026-10-13T10:00:00Z")),
            new SettlementRow(
                3,
                "psp_2",
                "req_2",
                "REFUND",
                "m_1",
                3000,
                "GBP",
                Instant.parse("2026-10-13T11:00:00.5Z")));
    assertThat(get("/ops/settlement-reports/" + id).body()).isEqualTo(report);
  }

  @Test
  void theSameFileAgainIsTheReportAlreadyStored() throws Exception {
    Response first = upload(HEADER + CAPTURE);
    Response again = upload(HEADER + CAPTURE);

    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body()).isEqualTo(first.body());
    assertThat(reportCount()).isEqualTo(1);
  }

  @Test
  void anInvalidReportIsKeptWithItsLineErrorsButNoRows() throws Exception {
    Response response = upload(HEADER + CAPTURE + BAD_AMOUNT + REFUND);

    assertThat(response.status()).isEqualTo(400);
    JsonNode problem = response.body();
    assertThat(problem.path("code").asText()).isEqualTo("INVALID_CSV");
    assertThat(problem.path("detail").asText())
        .isEqualTo(
            "Settlement report has 1 error; it is kept as INVALID version 1 and will not be"
                + " reconciled");
    assertThat(problem.path("errorCount").asInt()).isEqualTo(1);
    assertThat(problem.path("rowCount").asInt()).isEqualTo(3);
    assertThat(problem.path("errors").get(0).path("line").asInt()).isEqualTo(3);
    assertThat(problem.path("errors").get(0).path("column").asText()).isEqualTo("amount_minor");

    String id = problem.path("reportId").asText();
    JsonNode stored = get("/ops/settlement-reports/" + id).body();
    assertThat(stored.path("status").asText()).isEqualTo("INVALID");
    assertThat(stored.path("errors")).isEqualTo(problem.path("errors"));
    assertThat(rowCount(id)).isZero();

    Response again = upload(HEADER + CAPTURE + BAD_AMOUNT + REFUND);
    assertThat(again.status()).isEqualTo(400);
    assertThat(again.body().path("reportId").asText()).isEqualTo(id);
    assertThat(reportCount()).isEqualTo(1);
  }

  @Test
  void aCorrectedFileIsTheNextVersionAndEarlierOnesAreKept() throws Exception {
    String invalid = upload(HEADER + CAPTURE + BAD_AMOUNT).body().path("reportId").asText();
    Response corrected = upload(HEADER + CAPTURE + BAD_AMOUNT.replace("30.00", "3000"));
    Response amended = upload(HEADER + CAPTURE);

    assertThat(corrected.status()).isEqualTo(201);
    assertThat(corrected.body().path("version").asInt()).isEqualTo(2);
    assertThat(amended.body().path("version").asInt()).isEqualTo(3);

    JsonNode versions = get("/ops/settlement-reports?businessDate=" + DAY + "&psp=" + psp).body();
    assertThat(versions.findValuesAsText("version")).containsExactly("1", "2", "3");
    assertThat(versions.findValuesAsText("status")).containsExactly("INVALID", "VALID", "VALID");
    assertThat(versions.get(0).path("reportId").asText()).isEqualTo(invalid);
    assertThat(rowCount(corrected.body().path("reportId").asText())).isEqualTo(2);
  }

  @Test
  void concurrentUploadsOfDifferentFilesTakeDistinctVersions() throws Exception {
    int uploads = 8;
    List<Response> responses =
        concurrently(uploads, i -> upload(HEADER + CAPTURE.replace("req_1", "req_" + i)));

    assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
    Set<Integer> versions = new HashSet<>();
    responses.forEach(r -> versions.add(r.body().path("version").asInt()));
    assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
  }

  @Test
  void concurrentUploadsOfOneFileStoreItOnce() throws Exception {
    List<Response> responses = concurrently(8, i -> upload(HEADER + CAPTURE));

    assertThat(responses).extracting(Response::status).containsOnly(200, 201).contains(201);
    assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
    assertThat(responses.stream().map(r -> r.body().path("reportId").asText()).distinct())
        .hasSize(1);
    assertThat(reportCount()).isEqualTo(1);
  }

  interface Upload {
    Response run(int i) throws Exception;
  }

  List<Response> concurrently(int n, Upload upload) throws Exception {
    var start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
      List<Future<Response>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int index = i;
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return upload.run(index);
                }));
      }
      start.countDown();
      List<Response> responses = new ArrayList<>();
      for (var future : futures) {
        responses.add(future.get());
      }
      return responses;
    }
  }

  @Test
  void aFileOverTheSizeLimitIsRefusedAndNotKept() throws Exception {
    byte[] tooLarge = new byte[MAX_FILE_SIZE + 1];

    for (boolean chunked : new boolean[] {false, true}) {
      Response response = upload(tooLarge, "text/csv", OPS_KEY, chunked);

      assertThat(response.status()).as("chunked=" + chunked).isEqualTo(413);
      assertThat(response.body().path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
    }
    assertThat(reportCount()).isZero();

    Response atTheLimit = upload(new byte[MAX_FILE_SIZE], "text/csv", OPS_KEY, true);
    assertThat(atTheLimit.status()).isEqualTo(400);
    assertThat(atTheLimit.body().path("code").asText()).isEqualTo("INVALID_CSV");
  }

  /**
   * Far over the limit, and past the 2 MB Tomcat drains by default after a response: without a
   * larger max-swallow-size the connection is reset and the client never sees the 413 or its "split
   * the file".
   */
  @Test
  void aFileFarOverTheLimitStillGetsThe413() throws Exception {
    byte[] huge = new byte[5 * 1024 * 1024];

    for (boolean chunked : new boolean[] {false, true}) {
      Response response = upload(huge, "text/csv", OPS_KEY, chunked);

      assertThat(response.status()).as("chunked=" + chunked).isEqualTo(413);
      assertThat(response.body().path("detail").asText()).contains("split the file");
    }
    assertThat(reportCount()).isZero();
  }

  @Test
  void requestsThatAreNotAReportUploadAreRefusedBeforeAnythingIsStored() throws Exception {
    byte[] csv = (HEADER + CAPTURE).getBytes(StandardCharsets.UTF_8);

    assertThat(upload(csv, "application/json", OPS_KEY, false).status()).isEqualTo(415);
    assertThat(upload(csv, "text/csv", null, false).status()).isEqualTo(401);
    assertThat(upload(csv, "text/csv", "mk_merchant_key", false).status()).isEqualTo(401);
    psp = "Not+A+PSP";
    assertThat(upload(csv, "text/csv", OPS_KEY, false).body().path("code").asText())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(
            send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/ops/settlement-reports"))
                    .header("Authorization", "Bearer " + OPS_KEY)
                    .header("Content-Type", "text/csv")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(csv))
                    .build())
                .status())
        .isEqualTo(400);
    assertThat(get("/ops/settlement-reports/" + UUID.randomUUID()).status()).isEqualTo(404);
  }

  @Test
  void storedReportsAndRowsCannotBeChanged() throws Exception {
    String id = upload(HEADER + CAPTURE).body().path("reportId").asText();

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE settlement_rows SET amount_minor = 1 WHERE report_id = ?::uuid")
                    .param(id)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.sql("DELETE FROM settlement_reports WHERE id = ?::uuid").param(id).update())
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  void insertRow(String reportId) {
    jdbc.sql(
            """
            INSERT INTO settlement_rows (report_id, line_no, psp_reference, psp_request_id,
                                         txn_type, merchant_id, amount_minor, currency,
                                         succeeded_at)
            VALUES (?::uuid, 99, 'psp_x', 'req_x', 'CAPTURE', 'm_1', 1, 'GBP', now())""")
        .param(reportId)
        .update();
  }

  /**
   * A stored version is the file as uploaded: a later transaction cannot add rows to it, and an
   * INVALID report never has any.
   */
  @Test
  void noRowsCanBeAddedToAStoredReport() throws Exception {
    String valid = upload(HEADER + CAPTURE).body().path("reportId").asText();
    String invalid = upload(HEADER + BAD_AMOUNT).body().path("reportId").asText();

    for (String id : List.of(valid, invalid)) {
      assertThatThrownBy(() -> insertRow(id))
          .as(id)
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("is closed or INVALID");
    }
    assertThat(rowCount(valid)).isEqualTo(1);
    assertThat(rowCount(invalid)).isZero();
  }

  /** This test's PSP is not mock-psp, so a leading quote is part of the value it sent. */
  @Test
  void aLeadingQuoteIsKeptForAPspThatDoesNotEscapeFormulas() throws Exception {
    String id =
        upload(HEADER + "'+A1B2,req_1,CAPTURE,m_1,10000,GBP,2026-10-13T10:00:00Z\r\n")
            .body()
            .path("reportId")
            .asText();

    assertThat(
            jdbc.sql("SELECT psp_reference FROM settlement_rows WHERE report_id = ?::uuid")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo("'+A1B2");
  }

  @Test
  void theReportDateMustBeADay() throws Exception {
    Response response =
        send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/ops/settlement-reports?businessDate=2026-13-01"))
                .header("Authorization", "Bearer " + OPS_KEY)
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(HEADER))
                .build());

    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body().path("code").asText()).isEqualTo("VALIDATION_ERROR");
  }
}
