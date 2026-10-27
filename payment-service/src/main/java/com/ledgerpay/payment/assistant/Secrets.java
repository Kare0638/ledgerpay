package com.ledgerpay.payment.assistant;

import java.util.regex.Pattern;

/** Takes Google API keys out of any text that may be logged or returned (SECURITY.md). */
final class Secrets {

  // The classic Google API key and AI Studio's AQ. format, as in .gitleaks.toml.
  private static final Pattern GOOGLE_KEY =
      Pattern.compile("AIza[0-9A-Za-z_-]{35}|AQ\\.[A-Za-z0-9_-]{40,64}");

  private Secrets() {}

  static String redact(String text) {
    return text == null ? null : GOOGLE_KEY.matcher(text).replaceAll("<redacted>");
  }

  /** Also the exact key in use, whatever its format. */
  static String redact(String text, String key) {
    String redacted = redact(text);
    return redacted == null || key == null || key.isEmpty()
        ? redacted
        : redacted.replace(key, "<redacted>");
  }
}
