package com.ledgerpay.payment.psp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A scriptable PSP: records every request and answers with whatever the test sets. Requests are
 * served concurrently, like a real PSP, so slow replies do not queue behind each other.
 */
final class StubPsp {

  record Request(String method, String path, String body) {
    boolean isSubmit() {
      return method.equals("POST");
    }

    boolean mentions(String pspRequestId) {
      return path.endsWith("/" + pspRequestId) || body.contains("\"" + pspRequestId + "\"");
    }
  }

  record Reply(int status, String body, Duration delay) {
    static Reply of(int status, String body) {
      return new Reply(status, body, Duration.ZERO);
    }
  }

  private final HttpServer server;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private volatile Function<Request, Reply> responder = request -> Reply.of(500, "");

  StubPsp() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/v1/operations",
        exchange -> {
          var request =
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getPath(),
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          requests.add(request);
          Reply reply = responder.apply(request);
          try {
            Thread.sleep(reply.delay());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void respond(Function<Request, Reply> responder) {
    this.responder = responder;
  }

  void reset() {
    requests.clear();
    responder = request -> Reply.of(500, "");
  }

  List<Request> requestsFor(String pspRequestId) {
    return requests.stream().filter(r -> r.mentions(pspRequestId)).toList();
  }

  /** A mock-psp operation body (design §9.4). */
  static String operation(String pspRequestId, String pspReference, String status) {
    String succeededAt = status.equals("SUCCEEDED") ? "\"2026-10-01T09:00:00Z\"" : "null";
    String failure = status.equals("FAILED") ? "\"AMOUNT_MISMATCH\"" : "null";
    return """
        {"psp_reference": "%s", "psp_request_id": "%s", "status": "%s",
         "failure_reason": %s, "resource_version": 2, "succeeded_at": %s}"""
        .formatted(pspReference, pspRequestId, status, failure, succeededAt);
  }
}
