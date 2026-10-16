package com.ledgerpay.payment.reconciliation;

import java.util.UUID;

/**
 * Called inside the snapshot transaction, between its two reads of the books. Only tests provide
 * one: AT-18 commits a posting there and checks that the run does not see it.
 */
public interface SnapshotProbe {

  void betweenReads(UUID runId);
}
