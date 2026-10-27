package com.ledgerpay.payment.assistant;

import java.util.List;

/**
 * The assistant's output for one break, after validation (design §16): the fixed schema of the
 * {@code submit_analysis} tool. A draft for a person to read; it changes nothing.
 */
public record Analysis(
    LikelyCause likelyCause,
    Confidence confidence,
    String explanation,
    List<String> evidence,
    List<String> nextSteps) {

  public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
  }
}
