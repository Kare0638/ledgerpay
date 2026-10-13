package com.ledgerpay.notification.delivery;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Merchant webhook endpoints at {@code /hooks/{merchant}}: records each request and answers with
 * the statuses queued for that merchant, then 200 (or the merchant's fixed answer). Platform
 * threads, not virtual ones: the JDK server reads request bodies inside {@code synchronized}, which
 * pins carriers (ADR 0008).
 */
final class StubMerchant {

  /** Header names are lower-cased. */
  record Request(String merchant, Map<String, String> headers, String body) {}

  private final HttpServer server;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Queue<Integer>> statuses = new ConcurrentHashMap<>();
  private final Map<String, Integer> always = new ConcurrentHashMap<>();

  StubMerchant() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.createContext(
        "/hooks/",
        exchange -> {
          String merchant = exchange.getRequestURI().getPath().substring("/hooks/".length());
          byte[] body = exchange.getRequestBody().readAllBytes();
          Map<String, String> headers = new ConcurrentHashMap<>();
          exchange
              .getRequestHeaders()
              .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
          requests.add(new Request(merchant, headers, new String(body, StandardCharsets.UTF_8)));
          Integer status = statuses.getOrDefault(merchant, new ConcurrentLinkedQueue<>()).poll();
          if (status == null) {
            status = always.getOrDefault(merchant, 200);
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }

  void stop() {
    server.stop(0);
  }

  String url(String merchant) {
    return "http://localhost:" + server.getAddress().getPort() + "/hooks/" + merchant;
  }

  /** The next answers for this merchant, in order; 200 once they run out. */
  void answer(String merchant, Integer... next) {
    statuses.computeIfAbsent(merchant, m -> new ConcurrentLinkedQueue<>()).addAll(List.of(next));
  }

  /** Every answer for this merchant, once any queued ones run out. */
  void answerAlways(String merchant, int status) {
    always.put(merchant, status);
  }

  List<Request> requests(String merchant) {
    return requests.stream().filter(request -> request.merchant().equals(merchant)).toList();
  }
}
