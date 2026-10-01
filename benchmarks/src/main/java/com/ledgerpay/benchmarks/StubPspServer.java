package com.ledgerpay.benchmarks;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The PSP for {@link PspDispatchBenchmark}, run as its own process so that its threads never show
 * up in the benchmark JVM's thread counts. Answers every submit with PENDING after a fixed delay,
 * on a virtual thread per request so that it is never the bottleneck. Prints its port, then serves
 * until its stdin closes.
 *
 * <p>Usage: {@code java -cp benchmarks.jar com.ledgerpay.benchmarks.StubPspServer <delayMillis>}
 */
public final class StubPspServer {

  private static final Pattern REQUEST_ID = Pattern.compile("\"psp_request_id\":\"([^\"]+)\"");

  private StubPspServer() {}

  public static void main(String[] args) throws IOException {
    int delayMillis = Integer.parseInt(args[0]);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/v1/operations",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          Matcher id = REQUEST_ID.matcher(body);
          String requestId = id.find() ? id.group(1) : "unknown";
          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] reply =
              """
              {"psp_reference":"psp_%s","psp_request_id":"%s","status":"PENDING","resource_version":1}"""
                  .formatted(requestId, requestId)
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(201, reply.length);
          exchange.getResponseBody().write(reply);
          exchange.close();
        });
    server.start();
    System.out.println(server.getAddress().getPort());
    System.out.flush();
    // The benchmark holds our stdin open; when its JVM exits, for whatever reason, so do we.
    while (System.in.read() != -1) {}
    System.exit(0);
  }
}
