package com.ledgerpay.mockpsp.operation;

import static com.ledgerpay.mockpsp.operation.SubmitRejectedException.Reason.INVALID_REFERENCE;
import static com.ledgerpay.mockpsp.operation.SubmitRejectedException.Reason.INVALID_REQUEST;
import static com.ledgerpay.mockpsp.operation.SubmitRejectedException.Reason.REQUEST_ID_CONFLICT;

import com.ledgerpay.mockpsp.MockPspProperties;
import com.ledgerpay.mockpsp.webhook.WebhookEvents;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationService {

  public record Submitted(Operation operation, boolean created) {}

  private final Operations operations;
  private final WebhookEvents webhooks;
  private final MockPspProperties properties;

  public OperationService(
      Operations operations, WebhookEvents webhooks, MockPspProperties properties) {
    this.operations = operations;
    this.webhooks = webhooks;
    this.properties = properties;
  }

  /**
   * Records an operation, idempotent on {@code psp_request_id}: a repeat with the same parameters
   * returns the existing operation in its current state, with different parameters it is rejected.
   * A new operation is PENDING, or FAILED at once if the PSP declines it; either way its outcome is
   * sent as a webhook.
   */
  @Transactional
  public Submitted submit(SubmitRequest request) {
    Optional<Operation> existing = operations.findByRequestId(request.pspRequestId());
    if (existing.isPresent()) {
      return repeat(existing.get(), request);
    }

    FailureReason failure = null;
    OperationType parentType = request.type().parentType();
    if (parentType == null) {
      if (request.parentReference() != null) {
        throw new SubmitRejectedException(INVALID_REQUEST, "AUTHORIZE takes no parent_reference");
      }
    } else {
      if (request.parentReference() == null) {
        throw new SubmitRejectedException(
            INVALID_REQUEST, request.type() + " requires parent_reference");
      }
      // The lock serialises every decision about this parent: two captures, or refunds that
      // together exceed the capture, cannot both be accepted.
      Operation parent =
          operations
              .lock(request.parentReference())
              .filter(p -> p.type() == parentType)
              .filter(p -> p.merchantId().equals(request.merchantId()))
              .orElseThrow(
                  () ->
                      new SubmitRejectedException(
                          INVALID_REFERENCE,
                          "No " + parentType + " " + request.parentReference() + " for merchant"));
      failure = decline(request, parent);
    }

    Optional<Operation> inserted =
        operations.insert("psp_" + UUID.randomUUID(), request, failure, properties.settleDelay());
    if (inserted.isEmpty()) {
      // A concurrent request with the same ID committed first.
      return repeat(operations.findByRequestId(request.pspRequestId()).orElseThrow(), request);
    }
    Operation operation = inserted.get();
    if (operation.status() == OperationStatus.FAILED) {
      webhooks.enqueue(operation);
    }
    return new Submitted(operation, true);
  }

  public Optional<Operation> inquire(String pspRequestId) {
    return operations.findByRequestId(pspRequestId);
  }

  /** Why the PSP declines this operation, or null if it accepts it. */
  private FailureReason decline(SubmitRequest request, Operation parent) {
    if (parent.status() != OperationStatus.SUCCEEDED) {
      return FailureReason.PARENT_NOT_SUCCEEDED;
    }
    if (!parent.currency().equals(request.currency())) {
      return FailureReason.CURRENCY_MISMATCH;
    }
    return switch (request.type()) {
      case CAPTURE, VOID -> {
        if (request.amountMinor() != parent.amountMinor()) {
          yield FailureReason.AMOUNT_MISMATCH;
        }
        List<Operation> used =
            operations.activeChildren(
                parent.pspReference(), List.of(OperationType.CAPTURE, OperationType.VOID));
        yield used.isEmpty() ? null : FailureReason.AUTHORIZATION_ALREADY_USED;
      }
      case REFUND -> {
        long refunded =
            operations.activeChildren(parent.pspReference(), List.of(OperationType.REFUND)).stream()
                .mapToLong(Operation::amountMinor)
                .sum();
        yield refunded + request.amountMinor() > parent.amountMinor()
            ? FailureReason.REFUND_EXCEEDS_CAPTURE
            : null;
      }
      case AUTHORIZE -> throw new IllegalStateException("An authorisation has no parent");
    };
  }

  private static Submitted repeat(Operation existing, SubmitRequest request) {
    if (!existing.sameParameters(request)) {
      throw new SubmitRejectedException(
          REQUEST_ID_CONFLICT,
          "psp_request_id " + request.pspRequestId() + " was used with different parameters");
    }
    return new Submitted(existing, false);
  }
}
