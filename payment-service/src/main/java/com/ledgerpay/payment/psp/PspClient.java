package com.ledgerpay.payment.psp;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.net.http.HttpClient;
import java.time.Instant;
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

  private final RestClient http;

  public PspClient(PspProperties properties, RestClient.Builder builder) {
    // Not HttpURLConnection: through Spring it reports a read timeout as an unreadable response,
    // hiding that the outcome is unknown, and it may resend a POST when a reused connection fails
    // (sun.net.http.retryPost). Every resubmit here must be a decision the worker made.
    var client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
    var requestFactory = new JdkClientHttpRequestFactory(client);
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
