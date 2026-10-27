package com.ledgerpay.payment.assistant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An analysis as stored and shown, with its proposal if it made one.
 *
 * @param precedentIds the earlier analyses it was given as hints (#64), in order
 */
public record StoredAnalysis(
    UUID analysisId,
    UUID runId,
    int itemNo,
    String model,
    String status,
    Analysis analysis,
    String error,
    StoredProposal proposal,
    List<AgentResult.ToolCall> toolCalls,
    List<UUID> precedentIds,
    int turns,
    Integer tokens,
    Instant createdAt) {

  /**
   * @param outcome what the inquiry request returned once confirmed: SCHEDULED, or ALREADY_FINAL
   *     when the operation had its outcome by then
   */
  public record StoredProposal(
      UUID proposalId,
      UUID pspOperationId,
      String reason,
      String status,
      String decidedBy,
      Instant decidedAt,
      String outcome) {}
}
