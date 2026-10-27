package com.ledgerpay.payment.assistant;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A scripted Gemini: answers each generateContent call with the next reply queued, and records the
 * calls, headers included, so tests can check where the key went. CI never talks to Google.
 */
final class StubGemini {

  /** One call as received. */
  record Call(String uri, Map<String, List<String>> headers, String body) {
    String header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .map(e -> e.getValue().getFirst())
          .findFirst()
          .orElse(null);
    }
  }

  record Reply(int status, String body) {}

  private final HttpServer server;
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();

  StubGemini() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.createContext(
        "/",
        exchange -> {
          calls.add(
              new Call(
                  exchange.getRequestURI().toString(),
                  Map.copyOf(exchange.getRequestHeaders()),
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          Reply reply = replies.poll();
          if (reply == null) {
            reply =
                new Reply(
                    500,
                    "{\"error\": {\"status\": \"INTERNAL\", \"message\": \"no reply scripted\"}}");
          }
          byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void reset() {
    calls.clear();
    replies.clear();
  }

  List<Call> calls() {
    return List.copyOf(calls);
  }

  /** The model calls one tool, with a thought signature as Gemini's thinking models send. */
  StubGemini thenCall(String tool, String argsJson) {
    replies.add(
        new Reply(
            200,
            """
            {"candidates": [{"content": {"role": "model", "parts": [
               {"functionCall": {"name": "%s", "args": %s}, "thoughtSignature": "sig-%d"}]},
               "finishReason": "STOP"}],
             "usageMetadata": {"totalTokenCount": 100}}"""
                .formatted(tool, argsJson, replies.size() + 1)));
    return this;
  }

  /** The model answers in text, without calling a tool. */
  StubGemini thenText(String text) {
    replies.add(
        new Reply(
            200,
            """
            {"candidates": [{"content": {"role": "model", "parts": [{"text": "%s"}]}}],
             "usageMetadata": {"totalTokenCount": 10}}"""
                .formatted(text)));
    return this;
  }

  /** The model calls several tools in one turn, in this order: {tool, argsJson} pairs. */
  StubGemini thenCalls(String... toolsAndArgs) {
    StringBuilder parts = new StringBuilder();
    for (int i = 0; i < toolsAndArgs.length; i += 2) {
      parts
          .append(i == 0 ? "" : ",")
          .append(
              "{\"functionCall\": {\"name\": \"%s\", \"args\": %s}}"
                  .formatted(toolsAndArgs[i], toolsAndArgs[i + 1]));
    }
    replies.add(
        new Reply(
            200,
            """
            {"candidates": [{"content": {"role": "model", "parts": [%s]}, "finishReason": "STOP"}],
             "usageMetadata": {"totalTokenCount": 100}}"""
                .formatted(parts)));
    return this;
  }

  /** A 429 as Gemini sends it: the quota that ran out and how long to wait. */
  StubGemini thenQuota(String quotaId, String retryDelay) {
    replies.add(
        new Reply(
            429,
            """
            {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "Quota exceeded.",
              "details": [
                {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                 "violations": [{"quotaMetric": "generativelanguage.googleapis.com/x",
                                 "quotaId": "%s"}]},
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "%s"}]}}"""
                .formatted(quotaId, retryDelay)));
    return this;
  }

  StubGemini thenStatus(int status, String message) {
    replies.add(
        new Reply(
            status,
            "{\"error\": {\"status\": \"ERROR\", \"message\": \"%s\"}}".formatted(message)));
    return this;
  }
}
