package com.ledgerpay.mockpsp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Configures mock-psp's faults from a test (design §9.4). Plain JDK HTTP, so any module's tests can
 * use it against a mock-psp running under the {@code dev} profile, in-process or in a container.
 */
public final class MockPspFaults {

  private final HttpClient http = HttpClient.newHttpClient();
  private final URI faults;

  public MockPspFaults(String baseUrl) {
    this.faults = URI.create(baseUrl + "/_admin/faults");
  }

  /** Faults the next {@code times} new operations of the merchant, of {@code type} or any type. */
  public void inject(String merchantId, String type, String fault, Integer times) {
    String body =
        """
        {"merchant_id": "%s", "type": %s, "fault": "%s", "times": %s}"""
            .formatted(
                merchantId,
                type == null ? "null" : "\"" + type + "\"",
                fault,
                times == null ? "null" : times);
    send(
        HttpRequest.newBuilder(faults)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        201);
  }

  /** Faults every new operation of the merchant of this type. */
  public void inject(String merchantId, String type, String fault) {
    inject(merchantId, type, fault, null);
  }

  public void clear() {
    send(HttpRequest.newBuilder(faults).DELETE().build(), 204);
  }

  private void send(HttpRequest request, int expected) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != expected) {
        throw new IllegalStateException(
            "mock-psp answered " + response.statusCode() + ": " + response.body());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
