package com.ledgerpay.payment.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.api.DatabaseFailures;
import com.ledgerpay.payment.payment.MoneyTransaction;
import com.ledgerpay.payment.payment.MoneyTransaction.Outcome;
import com.ledgerpay.payment.payment.MoneyTransaction.Result;
import com.ledgerpay.payment.psp.PspOperationType;
import java.util.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns received webhooks into money transactions, one event per transaction: the event is marked
 * PROCESSED or QUARANTINED in the same transaction that applies it, so it is applied exactly once.
 */
@Component
public class InboxProcessor {

  private static final Logger log = LoggerFactory.getLogger(InboxProcessor.class);

  private final Inbox inbox;
  private final MoneyTransaction money;
  private final ObjectMapper json;
  private final TransactionTemplate transactions;

  public InboxProcessor(
      Inbox inbox, MoneyTransaction money, ObjectMapper json, TransactionTemplate transactions) {
    this.inbox = inbox;
    this.money = money;
    this.json = json;
    this.transactions = transactions;
  }

  /** Processes received events until none is left; returns how many were handled. */
  public int processAll() {
    int handled = 0;
    while (processOne()) {
      handled++;
    }
    return handled;
  }

  /** Processes the oldest received event, if any. */
  public boolean processOne() {
    Inbox.Pending[] claimed = new Inbox.Pending[1];
    try {
      return Boolean.TRUE.equals(
          transactions.execute(
              status -> {
                var pending = inbox.claimNext();
                if (pending.isEmpty()) {
                  return false;
                }
                claimed[0] = pending.get();
                finish(pending.get(), money.apply(outcome(pending.get())));
                return true;
              }));
    } catch (RuntimeException e) {
      if (claimed[0] == null || DatabaseFailures.isTemporary(e)) {
        throw e; // nothing claimed, or worth retrying: the event stays RECEIVED
      }
      // Retrying would fail the same way forever: park the event for a human instead.
      Inbox.Pending pending = claimed[0];
      log.error("Webhook {} could not be applied; quarantined", pending.eventId(), e);
      transactions.executeWithoutResult(
          status ->
              inbox.finish(
                  pending.provider(),
                  pending.eventId(),
                  "QUARANTINED",
                  "Processing failed: " + e.getMessage()));
      return true;
    }
  }

  private void finish(Inbox.Pending pending, Result result) {
    String reason =
        switch (result) {
          case Result.Applied applied -> null;
          case Result.AlreadyApplied applied -> "Already applied";
          case Result.Stale stale -> "Stale: older resource_version";
          case Result.Quarantined quarantined -> quarantined.reason();
        };
    if (result instanceof Result.Quarantined) {
      log.error("Webhook {} quarantined: {}", pending.eventId(), reason);
      inbox.finish(pending.provider(), pending.eventId(), "QUARANTINED", reason);
    } else {
      inbox.finish(pending.provider(), pending.eventId(), "PROCESSED", reason);
    }
  }

  private Outcome outcome(Inbox.Pending pending) {
    WebhookEvent event;
    try {
      event = json.readValue(pending.payload(), WebhookEvent.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored webhook " + pending.eventId() + " is unreadable", e);
    }
    return new Outcome(
        event.pspRequestId(),
        event.pspReference(),
        event.merchantId(),
        PspOperationType.valueOf(event.operationType()),
        Money.of(event.amountMinor(), Currency.getInstance(event.currency())),
        event.status().equals("SUCCEEDED"),
        event.status().equals("FAILED") ? "PSP reported FAILED" : null,
        event.status().equals("SUCCEEDED") ? event.occurredAt() : null,
        event.resourceVersion());
  }
}
