package com.ledgerpay.payment.psp;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** HTTP client for the PSP API (design §9.4). Never throws: every answer is a {@link PspResult}. */
@Component
public class PspClient {

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record SubmitBody(
      String pspRequestId,
      String merchantId,
      String type,
      String parentReference,
      long amountMinor,
      String currency) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record OperationBody(
      String pspReference,
      String pspRequestId,
      String status,
      String failureReason,
      Integer resourceVersion,
      Instant succeededAt) {}

  private static final Logger log = LoggerFactory.getLogger(PspClient.class);

  private final RestClient http;

  @Autowired
  public PspClient(PspProperties properties, RestClient.Builder builder) {
    this(properties, builder, Executors.newVirtualThreadPerTaskExecutor());
  }

  /**
   * @param httpExecutor runs the HTTP client's own tasks, including writing each request body. With
   *     none, Spring's {@link JdkClientHttpRequestFactory} falls back to a {@code
   *     SimpleAsyncTaskExecutor}, which starts a new platform thread for every request whatever
   *     thread made the call: JFR showed one per PSP call (#33). Null only for that benchmark
   *     baseline.
   */
  public PspClient(PspProperties properties, RestClient.Builder builder, Executor httpExecutor) {
    // Not HttpURLConnection: through Spring it reports a read timeout as an unreadable response,
    // hiding that the outcome is unknown, and it may resend a POST when a reused connection fails
    // (sun.net.http.retryPost). Every resubmit here must be a decision the worker made.
    var client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout());
    if (httpExecutor != null) {
      client.executor(httpExecutor);
    }
    if (ForkJoinPool.getCommonPoolParallelism() < 2) {
      // The HTTP client completes some stages with CompletableFuture's default executor, not its
      // own. With a common pool below 2, that default starts a platform thread per task: one per
      // PSP call on a machine with one or two CPUs. The Dockerfile sets the parallelism to 2.
      log.warn(
          "ForkJoinPool common parallelism is {}: every PSP call will start a platform thread."
              + " Set -Djava.util.concurrent.ForkJoinPool.common.parallelism=2",
          ForkJoinPool.getCommonPoolParallelism());
    }
    var requestFactory = new JdkClientHttpRequestFactory(client.build());
    requestFactory.setReadTimeout(properties.readTimeout());
    this.http =
        builder.baseUrl(properties.baseUrl().toString()).requestFactory(requestFactory).build();
  }

  public PspResult submit(ClaimedOperation operation) {
    var body =
        new SubmitBody(
            operation.pspRequestId(),
            operation.merchantId(),
            operation.type().name(),
            operation.parentReference(),
            operation.amountMinor(),
            operation.currency());
    try {
      return interpret(
          operation.pspRequestId(),
          http.post()
              .uri("/v1/operations")
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(OperationBody.class));
    } catch (RestClientResponseException e) {
      return failedCall("submit", e);
    } catch (RestClientException e) {
      return new PspResult.Unknown("submit: " + e.getMessage());
    }
  }

  public PspResult inquire(String pspRequestId) {
    try {
      return interpret(
          pspRequestId,
          http.get().uri("/v1/operations/{id}", pspRequestId).retrieve().body(OperationBody.class));
    } catch (HttpClientErrorException.NotFound e) {
      return new PspResult.NotFound();
    } catch (RestClientResponseException e) {
      return failedCall("inquire", e);
    } catch (RestClientException e) {
      return new PspResult.Unknown("inquire: " + e.getMessage());
    }
  }

  private static PspResult interpret(String pspRequestId, OperationBody body) {
    if (body == null
        || body.pspReference() == null
        || body.status() == null
        || !pspRequestId.equals(body.pspRequestId())) {
      return new PspResult.Unknown("Unexpected PSP response: " + body);
    }
    return switch (body.status()) {
      case "PENDING" -> new PspResult.Accepted(body.pspReference());
      case "SUCCEEDED", "FAILED" ->
          new PspResult.Final(
              body.pspReference(),
              body.status().equals("SUCCEEDED"),
              body.failureReason(),
              body.succeededAt(),
              body.resourceVersion() == null ? 0 : body.resourceVersion());
      default -> new PspResult.Unknown("Unexpected PSP status " + body.status());
    };
  }

  /**
   * An HTTP error status. A 4xx means the PSP refused the request itself (nothing recorded there),
   * which is a bug on one side or the other, not an outcome; it is kept as an error for review.
   */
  private static PspResult failedCall(String call, RestClientResponseException e) {
    String kind = e.getStatusCode().is4xxClientError() ? "rejected" : "failed";
    return new PspResult.Unknown(
        call + " " + kind + ": " + e.getStatusCode().value() + " " + e.getResponseBodyAsString());
  }
}
