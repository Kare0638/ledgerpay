package com.ledgerpay.payment.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keys stay out of every message and printout (SECURITY.md). All keys here are fake. */
class SecretsTest {

  static final String AQ_KEY = "AQ." + "Ab8RN6".repeat(8) + "xy";
  static final String AIZA_KEY = "AIza" + "SyD-fake_key".repeat(3);

  @Test
  void bothGoogleKeyFormatsAreRedacted() {
    assertThat(Secrets.redact("bad key " + AQ_KEY + " and " + AIZA_KEY.substring(0, 39) + "!"))
        .isEqualTo("bad key <redacted> and <redacted>!");
  }

  @Test
  void theKeyInUseIsRedactedWhateverItLooksLike() {
    assertThat(Secrets.redact("refused: plain-words-123", "plain-words-123"))
        .isEqualTo("refused: <redacted>");
  }

  AssistantProperties properties(String key, String file) {
    return new AssistantProperties(
        key,
        file,
        "gemini-3.5-flash",
        "https://example.invalid",
        Duration.ofSeconds(5),
        8,
        0,
        Duration.ZERO);
  }

  @Test
  void thePropertiesNeverPrintTheKey() {
    assertThat(properties(AQ_KEY, "").toString()).doesNotContain(AQ_KEY).contains("<redacted>");
  }

  @Test
  void theKeyCanComeFromAFileAndIsTrimmed(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("gemini_api_key");
    Files.writeString(file, AQ_KEY + "\n");

    assertThat(properties("", file.toString()).resolveKey()).isEqualTo(AQ_KEY);
    assertThat(properties("", "").resolveKey()).isEmpty();
  }

  @Test
  void anUnreadableKeyFileNamesThePathButNothingInIt(@TempDir Path dir) {
    String missing = dir.resolve("absent").toString();
    try {
      properties("", missing).resolveKey();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).isEqualTo("Cannot read the assistant key file " + missing);
      return;
    }
    throw new AssertionError("expected a failure");
  }
}
