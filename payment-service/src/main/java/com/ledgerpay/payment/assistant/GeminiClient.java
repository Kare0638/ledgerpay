package com.ledgerpay.payment.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini's generateContent and embedContent over plain HTTP (no SDK). The key goes in the {@code
 * x-goog-api-key} header only, never in the URL, where it would reach exception messages and access
 * logs; every error this class raises has key-like strings redacted. A refusal for rate or
 * availability (429, 500, 503) is retried with backoff; anything else is an error at once. A 429
 * says which quota ran out: a daily one fails at once (the free tier allows 20 requests a day per
 * model, and retrying only spends the next day's), and a wait Google asks for is honoured up to
 * {@link #MAX_SUGGESTED_WAIT}, beyond which the call fails rather than hold a request open.
 */
class GeminiClient {

  private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

  static final Duration MAX_SUGGESTED_WAIT = Duration.ofSeconds(60);

  /** The size of an embedding, as the column {@code assistant_analyses.break_embedding} has it. */
  static final int EMBEDDING_DIMENSIONS = 768;

  /** The model could not be reached or refused the call; the message is safe to show. */
  static class GeminiException extends RuntimeException {
    GeminiException(String message) {
      super(message);
    }
  }

  private final HttpClient http;
  private final ObjectMapper json;
  private final URI generate;
  private final URI embed;
  private final String key;
  private final Duration timeout;
  private final int maxRetries;
  private final Duration backoff;

  GeminiClient(AssistantProperties properties, String key, ObjectMapper json) {
    // Its own virtual-thread executor: without one the JDK client starts a platform thread per
    // request on small machines (ADR 0008).
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    this.json = json;
    String models = properties.baseUrl().replaceAll("/+$", "") + "/v1beta/models/";
    this.generate = URI.create(models + properties.model() + ":generateContent");
    this.embed = URI.create(models + properties.embeddingModel() + ":embedContent");
    this.key = key;
    this.timeout = properties.timeout();
    this.maxRetries = properties.maxRetries();
    this.backoff = properties.retryBackoff();
  }

  /** One turn of the conversation: the model's next content. */
  JsonNode generate(JsonNode request) {
    return post(generate, request);
  }

  /** The text's embedding, {@link #EMBEDDING_DIMENSIONS} values, for comparing breaks. */
  float[] embed(String text) {
    ObjectNode request = json.createObjectNode();
    request.putObject("content").putArray("parts").addObject().put("text", text);
    request.put("taskType", "SEMANTIC_SIMILARITY");
    request.put("outputDimensionality", EMBEDDING_DIMENSIONS);
    JsonNode values = post(embed, request).path("embedding").path("values");
    if (!values.isArray() || values.size() != EMBEDDING_DIMENSIONS) {
      throw new GeminiException(
          "Gemini answered an embedding of "
              + values.size()
              + " values, not "
              + EMBEDDING_DIMENSIONS);
    }
    float[] embedding = new float[EMBEDDING_DIMENSIONS];
    boolean zero = true;
    for (int i = 0; i < embedding.length; i++) {
      JsonNode value = values.get(i);
      if (!value.isNumber()) {
        throw new GeminiException("Gemini answered an embedding with a value that is no number");
      }
      embedding[i] = (float) value.asDouble();
      zero &= embedding[i] == 0;
    }
    if (zero) {
      // Its cosine distance to anything is NaN, which sorts unpredictably.
      throw new GeminiException("Gemini answered an embedding of zeros");
    }
    return embedding;
  }

  private JsonNode post(URI endpoint, JsonNode request) {
    byte[] body;
    try {
      body = json.writeValueAsBytes(request);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    HttpRequest call =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> response;
      try {
        response = http.send(call, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new GeminiException(
            safe("Gemini unreachable: " + e.getClass().getSimpleName() + " " + e.getMessage()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new GeminiException("Interrupted while waiting for Gemini");
      }
      int status = response.statusCode();
      if (status / 100 == 2) {
        try {
          return json.readTree(response.body());
        } catch (IOException e) {
          throw new GeminiException("Gemini answered with something that is not JSON");
        }
      }
      JsonNode error = errorOf(response.body());
      String reason = safe(errorMessage(error));
      boolean retryable = status == 429 || status == 500 || status == 503;
      if (!retryable || attempt >= maxRetries) {
        throw new GeminiException("Gemini answered " + status + ": " + reason);
      }
      Duration wait = backoff.multipliedBy(1L << attempt);
      if (status == 429) {
        Duration suggested = retryDelay(error);
        String quota = exhaustedQuota(error);
        if (quota.contains("PerDay")) {
          throw new GeminiException(
              safe(
                  "Gemini's daily quota is used up ("
                      + quota
                      + ")"
                      + (suggested == null ? "" : "; retry in " + minutes(suggested))));
        }
        if (suggested != null && suggested.compareTo(MAX_SUGGESTED_WAIT) > 0) {
          throw new GeminiException(
              safe("Gemini answered 429: " + reason + "; retry in " + minutes(suggested)));
        }
        if (suggested != null && suggested.compareTo(wait) > 0) {
          wait = suggested;
        }
      }
      log.warn("Gemini answered {} ({}); retrying in {} ms", status, reason, wait.toMillis());
      sleep(wait);
    }
  }

  private JsonNode errorOf(String body) {
    try {
      return json.readTree(body).path("error");
    } catch (IOException e) {
      return json.missingNode();
    }
  }

  private static String errorMessage(JsonNode error) {
    if (error.isMissingNode()) {
      return "unreadable error body";
    }
    return (error.path("status").asText("") + " " + error.path("message").asText("")).strip();
  }

  /** The quota a 429 names in its google.rpc.QuotaFailure detail, or "" when it names none. */
  private static String exhaustedQuota(JsonNode error) {
    for (JsonNode detail : error.path("details")) {
      if (detail.path("@type").asText().endsWith("google.rpc.QuotaFailure")) {
        for (JsonNode violation : detail.path("violations")) {
          String id = violation.path("quotaId").asText("");
          if (!id.isEmpty()) {
            return id;
          }
        }
      }
    }
    return "";
  }

  /** The wait a google.rpc.RetryInfo detail asks for ("2387s", "0.5s"), or null. */
  private static Duration retryDelay(JsonNode error) {
    for (JsonNode detail : error.path("details")) {
      if (detail.path("@type").asText().endsWith("google.rpc.RetryInfo")) {
        String delay = detail.path("retryDelay").asText("");
        if (delay.matches("\\d+(\\.\\d+)?s")) {
          double seconds = Double.parseDouble(delay.substring(0, delay.length() - 1));
          return Duration.ofMillis(Math.round(seconds * 1000));
        }
      }
    }
    return null;
  }

  private static String minutes(Duration wait) {
    long minutes = (wait.toSeconds() + 59) / 60;
    return minutes <= 1 ? wait.toSeconds() + " s" : minutes + " min";
  }

  private String safe(String text) {
    String redacted = Secrets.redact(text, key);
    return redacted.length() > 300 ? redacted.substring(0, 300) + "…" : redacted;
  }

  private static void sleep(Duration wait) {
    try {
      Thread.sleep(wait);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GeminiException("Interrupted while backing off");
    }
  }

  @Override
  public String toString() {
    return "GeminiClient[" + generate + ", " + embed + "]";
  }
}
