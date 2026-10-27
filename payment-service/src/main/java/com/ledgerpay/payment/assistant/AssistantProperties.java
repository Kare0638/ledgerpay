package com.ledgerpay.payment.assistant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The reconciliation assistant (design §16, #26). Without a key it is off: its endpoints answer 503
 * and nothing else changes. The key is a secret (SECURITY.md): it comes from the environment
 * ({@code GEMINI_API_KEY}) or a file such as a Compose secret, never from a tracked file.
 *
 * @param apiKey the Gemini API key, if given directly
 * @param apiKeyFile a file holding the key, such as {@code /run/secrets/gemini_api_key}; used when
 *     {@code apiKey} is empty
 * @param model the Gemini model, e.g. gemini-3.5-flash
 * @param baseUrl the Gemini API, overridden by tests with a stub
 * @param timeout one model call, thinking included
 * @param maxTurns model calls per analysis before it is given up
 * @param maxRetries retries of a call refused for rate or availability (429, 500, 503)
 * @param embeddingModel the Gemini model that embeds a break, to find precedents (#64)
 * @param maxPrecedents past analyses given with the question; 0 gives none, for comparing
 */
@ConfigurationProperties("ledgerpay.assistant")
public record AssistantProperties(
    @DefaultValue("") String apiKey,
    @DefaultValue("") String apiKeyFile,
    @DefaultValue("gemini-3.5-flash") String model,
    @DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
    @DefaultValue("120s") Duration timeout,
    @DefaultValue("8") int maxTurns,
    @DefaultValue("3") int maxRetries,
    @DefaultValue("2s") Duration retryBackoff,
    @DefaultValue("gemini-embedding-001") String embeddingModel,
    @DefaultValue("3") int maxPrecedents) {

  /** The key, from {@code apiKey} or else {@code apiKeyFile}; empty when there is none. */
  String resolveKey() {
    if (!apiKey.isBlank()) {
      return apiKey.strip();
    }
    if (apiKeyFile.isBlank()) {
      return "";
    }
    try {
      return Files.readString(Path.of(apiKeyFile)).strip();
    } catch (Exception e) {
      // The path is not secret; what the file holds is, and it is never read into a message.
      throw new IllegalStateException("Cannot read the assistant key file " + apiKeyFile);
    }
  }

  @Override
  public String toString() {
    // A record prints every component: never the key.
    return "AssistantProperties[model="
        + model
        + ", embeddingModel="
        + embeddingModel
        + ", maxPrecedents="
        + maxPrecedents
        + ", baseUrl="
        + baseUrl
        + ", key="
        + (apiKey.isBlank() ? "none" : "<redacted>")
        + ", apiKeyFile="
        + apiKeyFile
        + "]";
  }
}
