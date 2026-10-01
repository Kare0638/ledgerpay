package com.ledgerpay.common.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookSignatureTest {

  static final byte[] SECRET = "whsec_test".getBytes(StandardCharsets.UTF_8);
  static final byte[] BODY = "{\"event_id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
  static final long TIMESTAMP = 1_700_000_000L;
  static final Instant NOW = Instant.ofEpochSecond(TIMESTAMP);

  // printf '%s' '1700000000.{"event_id":"evt_1"}' | openssl dgst -sha256 -hmac 'whsec_test'
  static final String EXPECTED = "0344289cae2f5eb6d2640d79205fa65017efc017e224155b7ef95f784061172c";

  @Test
  void matchesAnIndependentlyComputedSignature() {
    assertThat(WebhookSignature.sign(SECRET, TIMESTAMP, BODY)).isEqualTo(EXPECTED);
  }

  @Test
  void acceptsTheCorrectSignature() {
    assertThat(WebhookSignature.verify(SECRET, "1700000000", BODY, EXPECTED, NOW)).isTrue();
  }

  @Test
  void rejectsAChangedBody() {
    byte[] tampered = "{\"event_id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);

    assertThat(WebhookSignature.verify(SECRET, "1700000000", tampered, EXPECTED, NOW)).isFalse();
  }

  @Test
  void rejectsTheWrongSecret() {
    byte[] other = "whsec_other".getBytes(StandardCharsets.UTF_8);

    assertThat(WebhookSignature.verify(other, "1700000000", BODY, EXPECTED, NOW)).isFalse();
  }

  @Test
  void rejectsATimestampThatWasNotSigned() {
    // Replaying the body and signature with a fresh timestamp must not pass.
    String fresh = String.valueOf(TIMESTAMP + 60);

    assertThat(WebhookSignature.verify(SECRET, fresh, BODY, EXPECTED, NOW)).isFalse();
  }

  @Test
  void rejectsUppercaseHex() {
    assertThat(WebhookSignature.verify(SECRET, "1700000000", BODY, EXPECTED.toUpperCase(), NOW))
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(longs = {-300, 300})
  void acceptsTimestampsAtTheEdgeOfTheTolerance(long offsetSeconds) {
    Instant now = NOW.plusSeconds(offsetSeconds);

    assertThat(WebhookSignature.verify(SECRET, "1700000000", BODY, EXPECTED, now)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {-301, 301})
  void rejectsTimestampsOutsideTheTolerance(long offsetSeconds) {
    Instant now = NOW.plusSeconds(offsetSeconds);

    assertThat(WebhookSignature.verify(SECRET, "1700000000", BODY, EXPECTED, now)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "1700000000.5", "99999999999999999999"})
  void rejectsMalformedTimestamps(String timestamp) {
    assertThat(WebhookSignature.verify(SECRET, timestamp, BODY, EXPECTED, NOW)).isFalse();
  }

  @Test
  void rejectsMissingHeaders() {
    assertThat(WebhookSignature.verify(SECRET, null, BODY, EXPECTED, NOW)).isFalse();
    assertThat(WebhookSignature.verify(SECRET, "1700000000", BODY, null, NOW)).isFalse();
  }
}
