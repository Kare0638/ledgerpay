package com.ledgerpay.mockpsp.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WebhookBackoffTest {

  @ParameterizedTest(name = "attempt {0} -> {1}s")
  @CsvSource({"0, 1", "1, 1", "2, 2", "3, 4", "4, 8", "6, 32", "7, 60", "20, 60", "1000, 60"})
  void doublesUpToSixtySeconds(int attempts, long seconds) {
    assertThat(WebhookEvents.backoff(attempts)).isEqualTo(Duration.ofSeconds(seconds));
  }
}
