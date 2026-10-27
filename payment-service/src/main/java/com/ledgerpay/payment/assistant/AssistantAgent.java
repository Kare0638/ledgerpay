package com.ledgerpay.payment.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.payment.reconciliation.ReconItem;
import com.ledgerpay.payment.reconciliation.ReconRun;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Drafts the analysis of one break with Gemini's function calling (design §16). The model reads
 * facts through read-only tools, may propose one inquiry, and must finish by calling {@code
 * submit_analysis}, whose arguments are the fixed output schema; they are validated here, with one
 * chance to correct them. It cannot reach the ledger: no tool writes, and a proposal is only
 * recorded, for a person to confirm.
 */
class AssistantAgent {

  static final String PROPOSE_INQUIRY = "propose_inquiry";
  static final String SUBMIT_ANALYSIS = "submit_analysis";
  private static final int MAX_INVALID_SUBMISSIONS = 2;

  static final String SYSTEM =
      """
      You analyse one break found by reconciling a payment service's books against its payment \
      service provider's (PSP) daily settlement report, for an operations engineer.

      Categories: MATCHED (no break); DUPLICATE_EXTERNAL (the same PSP reference on several \
      report lines); MISSING_INTERNAL (in the report, not in our books); MISSING_EXTERNAL \
      (succeeded in our books that day, not in the report); AMOUNT_MISMATCH (matched, but amount, \
      merchant, type, currency or day differ; see the diff); STATUS_MISMATCH (succeeded at the \
      PSP, PENDING or FAILED in our books).

      Rules:
      - The question comes with the break's facts and its operation's timeline. Call \
      get_operation_timeline or find_operations only to check something they do not show.
      - State only what the facts show. Cite IDs exactly as the tools gave them; never invent an \
      ID, amount or time.
      - You cannot change the books, and nobody should edit them to fix a break. The one action \
      you may take is propose_inquiry: ask the PSP for an operation's outcome, which a person \
      then confirms or rejects. Propose it only for this break's own operation, only while that \
      operation is PENDING, and at most once.
      - Finish by calling submit_analysis exactly once.
      """;

  private final GeminiClient gemini;
  private final AssistantTools tools;
  private final ObjectMapper json;
  private final int maxTurns;

  AssistantAgent(GeminiClient gemini, AssistantTools tools, ObjectMapper json, int maxTurns) {
    this.gemini = gemini;
    this.tools = tools;
    this.json = json;
    this.maxTurns = maxTurns;
  }

  AgentResult analyse(ReconRun run, ReconItem item) {
    // The facts every analysis needs come with the question, as design §16 has it: on Gemini's
    // free tier (20 requests a day per model) a call per lookup used up the day in four analyses.
    // The tools stay for anything further.
    ObjectNode given = json.createObjectNode();
    given.set(AssistantTools.GET_BREAK, tools.getBreak(run, item));
    if (item.internal() != null) {
      given.set(
          AssistantTools.GET_OPERATION_TIMELINE, tools.operationTimeline(item.internal().id()));
    }
    StringBuilder facts = new StringBuilder().append(given).append('\n');
    ArrayNode contents = json.createArrayNode();
    contents
        .addObject()
        .put("role", "user")
        .putArray("parts")
        .addObject()
        .put(
            "text",
            """
            Analyse break item %d (category %s) of reconciliation run %s. Below is what %s and \
            %s return for it; call tools only for anything more.

            %s"""
                .formatted(
                    item.itemNo(),
                    item.category(),
                    run.runId(),
                    AssistantTools.GET_BREAK,
                    AssistantTools.GET_OPERATION_TIMELINE,
                    given.toPrettyString()));

    List<AgentResult.ToolCall> calls = new ArrayList<>();
    AgentResult.Proposal proposal = null;
    int invalid = 0;
    String lastError = null;
    int tokens = 0;
    for (int turn = 1; turn <= maxTurns; turn++) {
      JsonNode response = gemini.generate(request(contents));
      tokens += response.path("usageMetadata").path("totalTokenCount").asInt(0);
      JsonNode content = response.path("candidates").path(0).path("content");
      if (content.isMissingNode() || !content.path("parts").isArray()) {
        String finish = response.path("candidates").path(0).path("finishReason").asText("none");
        return new AgentResult(
            AgentResult.Status.FAILED,
            null,
            "the model gave no content (finish reason " + finish + ")",
            null,
            calls,
            turn,
            tokens);
      }
      // Appended as returned: Gemini's thinking models need their own parts, signatures included,
      // in the history of the next call.
      contents.add(content);

      ArrayNode replies = json.createArrayNode();
      // Calls in the order made, but submit_analysis last: a proposal made in the same turn
      // must be recorded before the analysis completes.
      List<JsonNode> turnCalls = new ArrayList<>();
      for (JsonNode part : content.path("parts")) {
        if (part.has("functionCall")) {
          turnCalls.add(part.path("functionCall"));
        }
      }
      turnCalls.sort(
          Comparator.comparing(call -> call.path("name").asText().equals(SUBMIT_ANALYSIS)));
      for (JsonNode call : turnCalls) {
        String name = call.path("name").asText();
        JsonNode args = call.path("args");
        JsonNode reply;
        boolean ok = true;
        switch (name) {
          case AssistantTools.GET_BREAK -> reply = tools.getBreak(run, item);
          case AssistantTools.GET_OPERATION_TIMELINE -> {
            UUID id = uuid(args.path("pspOperationId").asText());
            ok = id != null;
            reply = ok ? tools.operationTimeline(id) : tools.error("pspOperationId must be a UUID");
          }
          case AssistantTools.FIND_OPERATIONS -> {
            String reference = args.path("reference").asText("").strip();
            ok = !reference.isEmpty() && reference.length() <= 200;
            reply = ok ? tools.findOperations(reference) : tools.error("reference is required");
          }
          case PROPOSE_INQUIRY -> {
            String refusal = proposalRefusal(item, proposal, args, facts.toString());
            ok = refusal == null;
            if (ok) {
              proposal =
                  new AgentResult.Proposal(
                      uuid(args.path("pspOperationId").asText()),
                      trim(args.path("reason").asText("").strip(), AnalysisValidator.MAX_LINE));
              reply =
                  json.createObjectNode()
                      .put("recorded", true)
                      .put("note", "Recorded as a proposal. A person confirms or rejects it.");
            } else {
              reply = tools.error(refusal);
            }
          }
          case SUBMIT_ANALYSIS -> {
            AnalysisValidator.Result result = AnalysisValidator.validate(args, facts.toString());
            calls.add(new AgentResult.ToolCall(name, args, result.valid()));
            if (result.valid()) {
              return new AgentResult(
                  AgentResult.Status.COMPLETED,
                  result.analysis(),
                  null,
                  proposal,
                  calls,
                  turn,
                  tokens);
            }
            invalid++;
            lastError = String.join("; ", result.errors());
            if (invalid >= MAX_INVALID_SUBMISSIONS) {
              return new AgentResult(
                  AgentResult.Status.INVALID_OUTPUT,
                  null,
                  "submit_analysis refused twice: " + lastError,
                  null,
                  calls,
                  turn,
                  tokens);
            }
            replies.add(
                functionResponse(
                    name, tools.error("Refused: " + lastError + ". Fix it and submit again.")));
            continue;
          }
          default -> {
            ok = false;
            reply = tools.error("no such tool");
          }
        }
        calls.add(new AgentResult.ToolCall(name, args, ok));
        // Every reply counts as facts the analysis may cite, so no reply repeats the model's own
        // arguments: an invented ID, looked up once, would otherwise become citable.
        facts.append(reply).append('\n');
        replies.add(functionResponse(name, reply));
      }
      if (replies.isEmpty()) {
        replies
            .addObject()
            .put("text", "Use the tools, and finish by calling " + SUBMIT_ANALYSIS + ".");
      }
      contents.addObject().put("role", "user").set("parts", replies);
    }
    return new AgentResult(
        invalid > 0 ? AgentResult.Status.INVALID_OUTPUT : AgentResult.Status.FAILED,
        null,
        "no valid analysis within "
            + maxTurns
            + " model calls"
            + (lastError == null ? "" : "; last refusal: " + lastError),
        null,
        calls,
        maxTurns,
        tokens);
  }

  /**
   * Why a proposal is refused, or null when it may be recorded. Its reason is what the person
   * deciding reads, so it may cite only IDs from the facts, like the analysis.
   */
  private String proposalRefusal(
      ReconItem item, AgentResult.Proposal earlier, JsonNode args, String facts) {
    if (earlier != null) {
      return "an inquiry was already proposed; at most one per analysis";
    }
    UUID id = uuid(args.path("pspOperationId").asText());
    if (id == null) {
      return "pspOperationId must be a UUID";
    }
    if (item.internal() == null || !id.equals(item.internal().id())) {
      return "only this break's own operation can be inquired about"
          + (item.internal() == null ? "; this break has none" : ": " + item.internal().id());
    }
    String status = tools.operationStatus(id);
    if (!"PENDING".equals(status)) {
      return "the operation is " + status + ", not PENDING: an inquiry can change nothing";
    }
    String reason = args.path("reason").asText("");
    if (reason.isBlank()) {
      return "a reason is required";
    }
    Set<String> unknown = AnalysisValidator.unknownIds(List.of(reason), facts);
    if (!unknown.isEmpty()) {
      return "the reason " + AnalysisValidator.citesUnknown(unknown);
    }
    return null;
  }

  private ObjectNode request(ArrayNode contents) {
    ObjectNode request = json.createObjectNode();
    request.putObject("systemInstruction").putArray("parts").addObject().put("text", SYSTEM);
    request.set("contents", contents);
    request.set("tools", declarations());
    // ANY: every turn is a tool call, so the analysis can only arrive through submit_analysis.
    request.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "ANY");
    return request;
  }

  ArrayNode declarations() {
    ArrayNode tools = json.createArrayNode();
    ArrayNode functions = tools.addObject().putArray("functionDeclarations");
    function(
        functions,
        AssistantTools.GET_BREAK,
        "The break under analysis: its category, the "
            + "report lines, our operation as the run's snapshot saw it, and the field diff.");
    ObjectNode timeline =
        function(
            functions,
            AssistantTools.GET_OPERATION_TIMELINE,
            "One of our PSP operations as "
                + "it is now: status, attempts and last error, its payment, its journals, and the "
                + "webhooks received for it.");
    property(timeline, "pspOperationId", "STRING", "The operation's ID (a UUID)", true);
    ObjectNode find =
        function(
            functions,
            AssistantTools.FIND_OPERATIONS,
            "Our operations with this PSP " + "reference or PSP request ID, if any.");
    property(find, "reference", "STRING", "A PSP reference or a PSP request ID", true);
    ObjectNode propose =
        function(
            functions,
            PROPOSE_INQUIRY,
            "Propose asking the PSP for this break's own PENDING "
                + "operation's outcome. Only recorded: a person confirms or rejects it.");
    property(propose, "pspOperationId", "STRING", "This break's operation ID", true);
    property(propose, "reason", "STRING", "Why the inquiry would resolve the break", true);
    ObjectNode submit =
        function(functions, SUBMIT_ANALYSIS, "Submit the analysis. Call exactly once, last.");
    ObjectNode cause = property(submit, "likelyCause", "STRING", "The most likely cause", true);
    ArrayNode causes = cause.putArray("enum");
    for (LikelyCause value : LikelyCause.values()) {
      causes.add(value.name());
    }
    ObjectNode confidence =
        property(submit, "confidence", "STRING", "How sure the facts make you", true);
    confidence.putArray("enum").add("LOW").add("MEDIUM").add("HIGH");
    property(
        submit,
        "explanation",
        "STRING",
        "A short explanation for an engineer, citing the " + "facts",
        true);
    property(submit, "evidence", "ARRAY", "The facts the conclusion rests on, one per entry", true)
        .putObject("items")
        .put("type", "STRING");
    property(submit, "nextSteps", "ARRAY", "What a person should do next", true)
        .putObject("items")
        .put("type", "STRING");
    return tools;
  }

  private ObjectNode function(ArrayNode functions, String name, String description) {
    ObjectNode function = functions.addObject().put("name", name).put("description", description);
    ObjectNode parameters = function.putObject("parameters").put("type", "OBJECT");
    parameters.putObject("properties");
    parameters.putArray("required");
    return function;
  }

  private ObjectNode property(
      ObjectNode function, String name, String type, String description, boolean required) {
    ObjectNode parameters = (ObjectNode) function.path("parameters");
    ObjectNode property =
        ((ObjectNode) parameters.path("properties"))
            .putObject(name)
            .put("type", type)
            .put("description", description);
    if (required) {
      ((ArrayNode) parameters.path("required")).add(name);
    }
    return property;
  }

  private ObjectNode functionResponse(String name, JsonNode response) {
    ObjectNode part = json.createObjectNode();
    part.putObject("functionResponse").put("name", name).set("response", response);
    return part;
  }

  private static UUID uuid(String text) {
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String trim(String text, int max) {
    return text.length() > max ? text.substring(0, max) : text;
  }
}
