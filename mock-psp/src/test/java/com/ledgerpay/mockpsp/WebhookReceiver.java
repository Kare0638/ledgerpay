package com.ledgerpay.mockpsp;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for payment-service's webhook endpoint: records every delivery exactly as received,
 * and answers 500 to bodies containing a marker registered with {@link #failWhenBodyContains}.
 */
public final class WebhookReceiver {

  public record Delivery(String timestamp, String signature, String contentType, byte[] body) {
    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private final HttpServer server;
  private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
  private final Set<String> failing = ConcurrentHashMap.newKeySet();

  public WebhookReceiver() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/webhooks/psp",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          var headers = exchange.getRequestHeaders();
          deliveries.add(
              new Delivery(
                  headers.getFirst("X-PSP-Timestamp"),
                  headers.getFirst("X-PSP-Signature"),
                  headers.getFirst("Content-Type"),
                  body));
          String text = new String(body, StandardCharsets.UTF_8);
          int status = failing.stream().anyMatch(text::contains) ? 500 : 200;
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/psp";
  }

  public void failWhenBodyContains(String marker) {
    failing.add(marker);
  }

  public void stopFailing(String marker) {
    failing.remove(marker);
  }

  /** Deliveries whose body mentions {@code marker}, e.g. a {@code psp_request_id}. */
  public List<Delivery> deliveriesMentioning(String marker) {
    return deliveries.stream().filter(d -> d.text().contains(marker)).toList();
  }
}
