package com.ledgerpay.payment.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;
import com.ledgerpay.payment.api.ResourceNotFoundException;
import com.ledgerpay.payment.psp.PspOperations;
import com.ledgerpay.payment.reconciliation.ReconCategory;
import com.ledgerpay.payment.reconciliation.ReconItem;
import com.ledgerpay.payment.reconciliation.ReconRun;
import com.ledgerpay.payment.reconciliation.ReconRuns;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconciliation assistant (design §16, #26): drafts an analysis of one break and, at most,
 * proposes an inquiry. Only a person confirming the proposal makes anything happen, and then
 * through the same inquiry an operator could request by hand.
 */
@Service
@EnableConfigurationProperties(AssistantProperties.class)
public class Assistant {

  private static final Logger log = LoggerFactory.getLogger(Assistant.class);

  private final ReconRuns runs;
  private final AssistantStore store;
  private final PspOperations operations;
  private final TransactionTemplate transactions;
  private final String model;
  private final AssistantAgent agent;

  public Assistant(
      AssistantProperties properties,
      ReconRuns runs,
      AssistantTools tools,
      AssistantStore store,
      PspOperations operations,
      TransactionTemplate transactions,
      ObjectMapper json) {
    this.runs = runs;
    this.store = store;
    this.operations = operations;
    this.transactions = transactions;
    this.model = properties.model();
    String key = properties.resolveKey();
    this.agent =
        key.isEmpty()
            ? null
            : new AssistantAgent(
                new GeminiClient(properties, key, json), tools, json, properties.maxTurns());
    log.info(
        "Reconciliation assistant {}", agent == null ? "off: no key configured" : "on, " + model);
  }

  public boolean enabled() {
    return agent != null;
  }

  /** Drafts and stores an analysis of one break; 503 when the model cannot be used now. */
  public StoredAnalysis analyse(UUID runId, int itemNo) {
    ReconRun run =
        runs.find(runId).orElseThrow(() -> new ResourceNotFoundException("Recon run " + runId));
    ReconItem item =
        runs.item(runId, itemNo)
            .orElseThrow(
                () -> new ResourceNotFoundException("Item " + itemNo + " of recon run " + runId));
    if (item.category() == ReconCategory.MATCHED) {
      throw new ApiException(ErrorCode.INVALID_STATE, "Item " + itemNo + " is MATCHED: no break");
    }
    if (agent == null) {
      throw new ApiException(
          ErrorCode.ASSISTANT_UNAVAILABLE,
          "The assistant is off: no Gemini key is configured (SECURITY.md)");
    }
    AgentResult result;
    try {
      result = agent.analyse(run, item);
    } catch (GeminiClient.GeminiException e) {
      log.warn("Assistant could not analyse item {} of run {}: {}", itemNo, runId, e.getMessage());
      throw new ApiException(ErrorCode.ASSISTANT_UNAVAILABLE, e.getMessage());
    }
    UUID id = store.insert(runId, itemNo, model, result);
    log.info(
        "Assistant analysis {} of item {} ({}) of run {}: {} in {} turns{}",
        id,
        itemNo,
        item.category(),
        runId,
        result.analysis() == null ? result.status() : result.analysis().likelyCause(),
        result.turns(),
        result.proposal() == null ? "" : ", inquiry proposed");
    return store.find(id).orElseThrow();
  }

  public StoredAnalysis get(UUID analysisId) {
    return store
        .find(analysisId)
        .orElseThrow(() -> new ResourceNotFoundException("Assistant analysis " + analysisId));
  }

  /**
   * A person confirms a proposed inquiry: the operation becomes due for an inquiry, exactly as POST
   * /ops/psp-operations/{id}/inquiry would make it. 409 while a worker is calling the PSP for it,
   * so that the confirmation can be retried.
   */
  public StoredAnalysis confirm(UUID proposalId, String decidedBy) {
    return decide(proposalId, decidedBy, true);
  }

  public StoredAnalysis reject(UUID proposalId, String decidedBy) {
    return decide(proposalId, decidedBy, false);
  }

  private StoredAnalysis decide(UUID proposalId, String decidedBy, boolean confirm) {
    transactions.executeWithoutResult(
        status -> {
          String current =
              store
                  .lockProposal(proposalId)
                  .orElseThrow(
                      () -> new ResourceNotFoundException("Inquiry proposal " + proposalId));
          if (!current.equals("PROPOSED")) {
            throw new ApiException(
                ErrorCode.INVALID_STATE,
                "Inquiry proposal " + proposalId + " is already " + current);
          }
          if (!confirm) {
            store.decide(proposalId, "REJECTED", decidedBy, null);
            return;
          }
          UUID operation =
              store.findByProposal(proposalId).orElseThrow().proposal().pspOperationId();
          String outcome =
              switch (operations.requestInquiry(operation)) {
                case SCHEDULED -> "SCHEDULED";
                case FINAL -> "ALREADY_FINAL";
                case NOT_FOUND -> "OPERATION_NOT_FOUND";
                case IN_FLIGHT ->
                    throw new ApiException(
                        ErrorCode.INVALID_STATE,
                        "PSP operation "
                            + operation
                            + " is being called right now; confirm again"
                            + " when the call ends");
              };
          store.decide(proposalId, "CONFIRMED", decidedBy, outcome);
          log.info(
              "Inquiry proposal {} confirmed by {}: {} for operation {}",
              proposalId,
              decidedBy,
              outcome,
              operation);
        });
    return store.findByProposal(proposalId).orElseThrow();
  }
}
