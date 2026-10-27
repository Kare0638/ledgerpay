package com.ledgerpay.payment.assistant;

import java.util.List;
import java.util.UUID;

/**
 * What one analysis produced. Only a COMPLETED one has an analysis, and only it may carry a
 * proposal, which nothing acts on until a person confirms it.
 *
 * @param toolCalls every tool call, in order, for audit
 */
record AgentResult(
    Status status,
    Analysis analysis,
    String error,
    Proposal proposal,
    List<ToolCall> toolCalls,
    int turns,
    int tokens) {

  enum Status {
    COMPLETED,
    INVALID_OUTPUT,
    FAILED
  }

  record Proposal(UUID pspOperationId, String reason) {}

  /**
   * @param ok false when the tool refused the call (unknown tool, bad argument, refused proposal)
   */
  record ToolCall(String tool, Object args, boolean ok) {}
}
