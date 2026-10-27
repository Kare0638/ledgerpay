package com.ledgerpay.payment.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * What kind of break this is, as text to embed (#64): the category, which fields differ, and how
 * our operation and its payment stand, without IDs, times or amounts. Two lost webhooks on
 * different days read alike, so their embeddings are close; a precedent is found by its shape, not
 * by sharing an ID with the break.
 */
final class BreakSignature {

  private static final Pattern WORD = Pattern.compile("[A-Z][A-Z_]{0,31}");

  private BreakSignature() {}

  /**
   * @param given what {@code get_break} and {@code get_operation_timeline} returned, keyed by tool
   */
  static String of(JsonNode given) {
    JsonNode breakFacts = given.path(AssistantTools.GET_BREAK);
    JsonNode timeline = given.path(AssistantTools.GET_OPERATION_TIMELINE);
    List<String> lines = new ArrayList<>();
    lines.add("category: " + breakFacts.path("category").asText());
    lines.add("fields that differ: " + names(breakFacts.path("diff")));
    JsonNode reportLines = breakFacts.path("reportLines");
    lines.add(
        "report lines: "
            + reportLines.size()
            + (reportLines.isEmpty() ? "" : ", " + values(reportLines, "txnType")));
    JsonNode ours = breakFacts.path("ourOperationAtSnapshot");
    lines.add(
        "our operation at the snapshot: "
            + (ours.isMissingNode() || ours.isNull()
                ? "none"
                : ours.path("type").asText() + " " + ours.path("status").asText()));
    JsonNode operation = timeline.path("operation");
    if (!operation.isMissingNode()) {
      lines.add(
          "our operation now: "
              + operation.path("status").asText()
              + ", attempts "
              + operation.path("attempts").asInt()
              + ", consecutive failures "
              + operation.path("consecutiveFailures").asInt()
              + ", needs review "
              + operation.path("needsReview").asBoolean());
      // Whether there was one, not its text: that comes from the PSP or the network, may hold
      // amounts, times and IDs of any form, and would reach the question of every similar break.
      lines.add("last error: " + (operation.hasNonNull("lastError") ? "yes" : "none"));
      lines.add("payment: " + timeline.path("payment").path("status").asText());
      lines.add("journals: " + values(timeline.path("journals"), "entryType"));
      lines.add(
          "webhooks received: " + values(timeline.path("webhooksReceived"), "reportedStatus"));
    }
    return AnalysisValidator.ID.matcher(String.join("\n", lines)).replaceAll("<id>");
  }

  private static String names(JsonNode object) {
    List<String> names = new ArrayList<>();
    object.fieldNames().forEachRemaining(names::add);
    return names.isEmpty() ? "none" : String.join(", ", names.stream().sorted().toList());
  }

  private static String values(JsonNode array, String field) {
    List<String> values =
        StreamSupport.stream(array.spliterator(), false)
            .map(node -> word(node.path(field).asText()))
            .toList();
    return values.isEmpty() ? "none" : String.join(", ", values);
  }

  /**
   * A status or type as it is, anything else as "other": a webhook's reported status is whatever
   * its payload said, and free text must not reach the question of every similar break.
   */
  private static String word(String value) {
    return WORD.matcher(value).matches() ? value : "other";
  }
}
