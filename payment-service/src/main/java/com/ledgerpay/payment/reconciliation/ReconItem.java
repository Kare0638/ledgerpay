package com.ledgerpay.payment.reconciliation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One finding of a run: the report lines and our operation it concerns, either of which may be
 * absent, and for a field conflict what differs.
 *
 * @param diff field name to {@code {external, internal}} values; empty when nothing differs
 */
public record ReconItem(
    int itemNo,
    ReconCategory category,
    String pspReference,
    List<Integer> externalLineNos,
    InternalOperation internal,
    Map<String, Difference> diff) {

  public record Difference(Object external, Object internal) {}

  public UUID pspOperationId() {
    return internal == null ? null : internal.id();
  }
}
