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
    @DefaultValue("2s") Duration retryBackoff) {

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
        + ", baseUrl="
        + baseUrl
        + ", key="
        + (apiKey.isBlank() ? "none" : "<redacted>")
        + ", apiKeyFile="
        + apiKeyFile
        + "]";
  }
}
