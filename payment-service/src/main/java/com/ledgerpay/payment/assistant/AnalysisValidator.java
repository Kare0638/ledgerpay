package com.ledgerpay.payment.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks what the model submits before anyone sees it (design §16): the fixed schema, and that it
 * cites only IDs the tools gave it. A draft that names a payment or operation the facts never
 * mentioned is refused, not shown.
 */
final class AnalysisValidator {

  static final int MAX_EXPLANATION = 1_200;
  static final int MAX_LINE = 300;
  static final int MAX_EVIDENCE = 8;
  static final int MAX_STEPS = 5;

  /** A payment, operation or PSP ID, as an analysis may cite one. */
  static final Pattern ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
              + "|\\b(?:psp|req)_[A-Za-z0-9_-]+");

  /** The analysis, or why it was refused (each reason fit to send back to the model). */
  record Result(Analysis analysis, List<String> errors) {
    boolean valid() {
      return errors.isEmpty();
    }
  }

  private AnalysisValidator() {}

  /**
   * @param facts everything the tools returned in this analysis, as JSON text
   */
  static Result validate(JsonNode args, String facts) {
    List<String> errors = new ArrayList<>();
    LikelyCause cause =
        enumValue(LikelyCause.class, args.path("likelyCause"), "likelyCause", errors);
    Analysis.Confidence confidence =
        enumValue(Analysis.Confidence.class, args.path("confidence"), "confidence", errors);
    String explanation = args.path("explanation").asText("");
    if (explanation.isBlank()) {
      errors.add("explanation is required");
    } else if (explanation.length() > MAX_EXPLANATION) {
      errors.add("explanation is longer than " + MAX_EXPLANATION + " characters");
    }
    List<String> evidence = lines(args.path("evidence"), "evidence", 1, MAX_EVIDENCE, errors);
    List<String> nextSteps = lines(args.path("nextSteps"), "nextSteps", 0, MAX_STEPS, errors);

    List<String> texts = new ArrayList<>(evidence);
    texts.addAll(nextSteps);
    texts.add(explanation);
    Set<String> unknown = unknownIds(texts, facts);
    if (!unknown.isEmpty()) {
      errors.add(citesUnknown(unknown));
    }
    return new Result(
        errors.isEmpty()
            ? new Analysis(cause, confidence, explanation.strip(), evidence, nextSteps)
            : null,
        List.copyOf(errors));
  }

  /** The payment, operation and PSP IDs in {@code texts} that {@code facts} never mentions. */
  static Set<String> unknownIds(List<String> texts, String facts) {
    Set<String> unknown = new LinkedHashSet<>();
    for (String text : texts) {
      Matcher id = ID.matcher(text);
      while (id.find()) {
        if (!facts.contains(id.group())) {
          unknown.add(id.group());
        }
      }
    }
    return unknown;
  }

  static String citesUnknown(Set<String> unknown) {
    return "cites IDs the tools never returned: "
        + String.join(", ", unknown)
        + "; cite only IDs from the facts";
  }

  private static <E extends Enum<E>> E enumValue(
      Class<E> type, JsonNode value, String field, List<String> errors) {
    try {
      return Enum.valueOf(type, value.asText(""));
    } catch (IllegalArgumentException e) {
      errors.add(field + " must be one of " + List.of(type.getEnumConstants()));
      return null;
    }
  }

  private static List<String> lines(
      JsonNode value, String field, int min, int max, List<String> errors) {
    List<String> lines = new ArrayList<>();
    if (!value.isMissingNode() && !value.isNull() && !value.isArray()) {
      errors.add(field + " must be a list of strings");
      return lines;
    }
    value.forEach(line -> lines.add(line.asText("").strip()));
    lines.removeIf(String::isEmpty);
    if (lines.size() < min || lines.size() > max) {
      errors.add(field + " must have " + min + " to " + max + " entries");
    }
    if (lines.stream().anyMatch(line -> line.length() > MAX_LINE)) {
      errors.add(field + " entries must be at most " + MAX_LINE + " characters");
    }
    return List.copyOf(lines);
  }
}
