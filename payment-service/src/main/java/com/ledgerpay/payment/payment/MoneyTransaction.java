package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.ledger.EntryType;
import com.ledgerpay.payment.ledger.Ledger;
import com.ledgerpay.payment.ledger.PostingRules;
import com.ledgerpay.payment.outbox.Outbox;
import com.ledgerpay.payment.psp.ClaimedOperation;
import com.ledgerpay.payment.psp.PspOperationType;
import com.ledgerpay.payment.psp.PspOperations;
import com.ledgerpay.payment.psp.PspOperations.OperationRow;
import com.ledgerpay.payment.psp.PspOutcomeHandler;
import com.ledgerpay.payment.psp.PspProperties;
import com.ledgerpay.payment.psp.PspResult;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a PSP's final answer (design §9.3), whether it came by webhook or by inquiry: operation
 * and payment status, captured amount, journal and outbox event, all in one transaction. Locks are
 * always taken payment first, then operation, so a webhook and an inquiry racing for the same
 * outcome serialise instead of deadlocking, and the loser finds the work already done.
 */
@Service
public class MoneyTransaction implements PspOutcomeHandler {

  private static final Logger log = LoggerFactory.getLogger(MoneyTransaction.class);

  /** What the PSP reported. Only a webhook can report PENDING; an inquiry applies final answers. */
  public enum ReportedStatus {
    PENDING,
    SUCCEEDED,
    FAILED
  }

  /** An answer as reported, with the fields that must agree with what we asked for. */
  public record Outcome(
      String pspRequestId,
      String pspReference,
      String merchantId,
      PspOperationType type,
      Money amount,
      ReportedStatus status,
      String failureReason,
      Instant succeededAt,
      int resourceVersion) {

    boolean succeeded() {
      return status == ReportedStatus.SUCCEEDED;
    }
  }

  public sealed interface Result {
    record Applied() implements Result {}

    /** The operation already has this outcome: a duplicate, or the webhook and inquiry raced. */
    record AlreadyApplied() implements Result {}

    /** Older than what we hold; it cannot move state backwards. */
    record Stale(String reason) implements Result {}

    /** A PENDING report for an operation that is still pending: nothing to apply. */
    record NotFinal() implements Result {}

    /** Does not match our records or contradicts a final state; never touches money. */
    record Quarantined(String reason) implements Result {}
  }

  private final Payments payments;
  private final Refunds refunds;
  private final PspOperations operations;
  private final Ledger ledger;
  private final Outbox outbox;
  private final String psp;

  public MoneyTransaction(
      Payments payments,
      Refunds refunds,
      PspOperations operations,
      Ledger ledger,
      Outbox outbox,
      PspProperties properties) {
    this.payments = payments;
    this.refunds = refunds;
    this.operations = operations;
    this.ledger = ledger;
    this.outbox = outbox;
    this.psp = properties.provider();
  }

  @Transactional
  public Result apply(Outcome outcome) {
    Optional<UUID> paymentId = operations.paymentIdOf(outcome.pspRequestId());
    if (paymentId.isEmpty()) {
      return new Result.Quarantined("Unknown psp_request_id " + outcome.pspRequestId());
    }
    Payment payment = payments.lock(paymentId.get()).orElseThrow();
    OperationRow operation = operations.lock(outcome.pspRequestId());

    String mismatch = mismatch(outcome, payment, operation);
    if (mismatch != null) {
      return new Result.Quarantined(mismatch);
    }
    if (outcome.status() == ReportedStatus.PENDING) {
      // Never applied: PENDING is where every operation starts. After an outcome it is an event
      // overtaken in delivery (design §9.3: a final state never regresses), not a contradiction.
      return operation.isFinal()
          ? new Result.Stale("Stale: PENDING after " + operation.status())
          : new Result.NotFinal();
    }
    if (operation.isFinal()) {
      // A final state never changes, whatever arrives later. Two different final answers cannot
      // both be true at any resource_version, so the contradiction goes to a human.
      return operation.status().equals(outcome.status().name())
          ? new Result.AlreadyApplied()
          : new Result.Quarantined(
              "Operation is " + operation.status() + " but the PSP now reports otherwise");
    }
    if (outcome.resourceVersion() < operation.resourceVersion()) {
      return new Result.Stale("Stale: older resource_version");
    }

    operations.complete(
        operation.id(),
        outcome.succeeded(),
        outcome.pspReference(),
        outcome.succeededAt(),
        outcome.resourceVersion(),
        outcome.failureReason());
    if (!outcome.succeeded()) {
      log.warn(
          "PSP rejected {} {} for payment {}: {}",
          operation.type(),
          operation.pspRequestId(),
          payment.id(),
          outcome.failureReason());
    }

    if (operation.type() == PspOperationType.REFUND) {
      applyRefund(payment, operation, outcome);
      return new Result.Applied();
    }
    PaymentStatus next = next(payment.status(), operation.type(), outcome.succeeded());
    Money captured = payment.captured();
    if (operation.type() == PspOperationType.CAPTURE && outcome.succeeded()) {
      captured = operation.amount();
      ledger.post(
          operation.id(),
          EntryType.CAPTURE,
          PostingRules.capture(
              psp,
              payment.merchantId(),
              operation.amount(),
              payments.feeBps(payment.merchantId())));
    }
    payments.update(payment.id(), payment.status().transitionTo(next), captured);
    outbox.append(
        payment.id(),
        eventType(operation.type(), outcome.succeeded()),
        event(payment, next, captured, operation, outcome));
    return new Result.Applied();
  }

  /** The inquiry path: the outcome is for an operation we hold, so its own fields are trusted. */
  @Override
  @Transactional
  public void apply(ClaimedOperation claimed, PspResult.Final outcome) {
    Result result =
        apply(
            new Outcome(
                claimed.pspRequestId(),
                outcome.pspReference(),
                claimed.merchantId(),
                claimed.type(),
                Money.of(claimed.amountMinor(), Currency.getInstance(claimed.currency())),
                outcome.succeeded() ? ReportedStatus.SUCCEEDED : ReportedStatus.FAILED,
                outcome.failureReason(),
                outcome.succeededAt(),
                outcome.resourceVersion()));
    if (result instanceof Result.Quarantined quarantined) {
      log.error(
          "Inquiry result for {} not applied: {}", claimed.pspRequestId(), quarantined.reason());
    }
  }

  /**
   * A refund's answer (design §5.4). The payment's status does not change; its reservation is
   * released either way, into the refunded amount and a refund journal only on success. A refund
   * whose outcome is unknown never reaches this point, so its reservation stays.
   */
  private void applyRefund(Payment payment, OperationRow operation, Outcome outcome) {
    boolean succeeded = outcome.succeeded();
    payments.settleRefund(payment.id(), operation.amount(), succeeded);
    if (succeeded) {
      ledger.post(
          operation.id(),
          EntryType.REFUND,
          PostingRules.refund(psp, payment.merchantId(), operation.amount()));
    }
    refunds.complete(
        operation.refundId(), succeeded ? RefundStatus.SUCCEEDED : RefundStatus.FAILED);
    Refund refund = refunds.find(payment.merchantId(), operation.refundId()).orElseThrow();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("refundId", refund.id());
    event.put("paymentId", payment.id());
    event.put("merchantId", payment.merchantId());
    event.put("merchantReference", refund.merchantReference());
    event.put("status", refund.status().name());
    event.put("amountMinor", refund.amount().minor());
    event.put("currency", refund.amount().currency().getCurrencyCode());
    Money refunded = succeeded ? payment.refunded().plus(operation.amount()) : payment.refunded();
    event.put("refundedMinor", refunded.minor());
    event.put("pspOperationId", operation.id());
    event.put("pspReference", outcome.pspReference());
    if (!succeeded) {
      event.put("failureReason", outcome.failureReason());
    }
    outbox.append(payment.id(), succeeded ? "RefundSucceeded" : "RefundFailed", event);
  }

  private static String mismatch(Outcome outcome, Payment payment, OperationRow operation) {
    if (!payment.merchantId().equals(outcome.merchantId())) {
      return "merchant_id " + outcome.merchantId() + " does not match the payment";
    }
    if (operation.type() != outcome.type()) {
      return "type " + outcome.type() + " does not match operation type " + operation.type();
    }
    if (!operation.amount().equals(outcome.amount())) {
      return "amount "
          + outcome.amount()
          + " does not match operation amount "
          + operation.amount();
    }
    if (operation.pspReference() != null
        && !operation.pspReference().equals(outcome.pspReference())) {
      return "psp_reference "
          + outcome.pspReference()
          + " does not match "
          + operation.pspReference();
    }
    if (outcome.succeeded() && outcome.succeededAt() == null) {
      return "A success without succeeded_at";
    }
    return null;
  }

  private static PaymentStatus next(
      PaymentStatus current, PspOperationType type, boolean succeeded) {
    return switch (type) {
      case AUTHORIZE -> succeeded ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
      case CAPTURE -> succeeded ? PaymentStatus.CAPTURED : PaymentStatus.AUTHORIZED;
      case VOID -> succeeded ? PaymentStatus.VOIDED : PaymentStatus.AUTHORIZED;
      case REFUND -> throw new IllegalArgumentException("A refund leaves the payment status alone");
    };
  }

  private static String eventType(PspOperationType type, boolean succeeded) {
    return switch (type) {
      case AUTHORIZE -> succeeded ? "PaymentAuthorized" : "PaymentDeclined";
      case CAPTURE -> succeeded ? "PaymentCaptured" : "PaymentCaptureFailed";
      case VOID -> succeeded ? "PaymentVoided" : "PaymentVoidFailed";
      case REFUND -> throw new IllegalArgumentException("Refund events are built by applyRefund");
    };
  }

  private static Map<String, Object> event(
      Payment payment,
      PaymentStatus status,
      Money captured,
      OperationRow operation,
      Outcome outcome) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("paymentId", payment.id());
    event.put("merchantId", payment.merchantId());
    event.put("merchantReference", payment.merchantReference());
    event.put("status", status.name());
    event.put("amountMinor", payment.amount().minor());
    event.put("capturedMinor", captured.minor());
    event.put("currency", payment.amount().currency().getCurrencyCode());
    event.put("pspOperationId", operation.id());
    event.put("pspReference", outcome.pspReference());
    if (!outcome.succeeded()) {
      event.put("failureReason", outcome.failureReason());
    }
    return event;
  }
}
