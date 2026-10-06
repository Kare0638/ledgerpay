package com.ledgerpay.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.idempotency.IdempotentExecutor;
import com.ledgerpay.payment.idempotency.IdempotentResponse;
import com.ledgerpay.payment.payment.Payment;
import com.ledgerpay.payment.payment.PaymentService;
import com.ledgerpay.payment.payment.Refund;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

  static final String CREATE_SCOPE = "POST /v1/payments";
  static final String CAPTURE_SCOPE = "POST /v1/payments/{id}/capture";
  static final String VOID_SCOPE = "POST /v1/payments/{id}/void";
  static final String REFUND_SCOPE = "POST /v1/payments/{id}/refunds";
  static final String IDEMPOTENCY_KEY = "Idempotency-Key";
  static final int MAX_KEY_LENGTH = 255;

  private final PaymentService payments;
  private final IdempotentExecutor idempotency;

  public PaymentController(PaymentService payments, IdempotentExecutor idempotency) {
    this.payments = payments;
    this.idempotency = idempotency;
  }

  @PostMapping
  ResponseEntity<JsonNode> create(
      @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant,
      @RequestHeader(IDEMPOTENCY_KEY) String key,
      @Valid @RequestBody CreatePaymentRequest request) {
    requireValidKey(key);
    Money amount = Money.positive(request.amountMinor(), Currency.getInstance(request.currency()));
    return respond(
        idempotency.execute(
            merchant.id(),
            CREATE_SCOPE,
            key,
            request,
            () -> accepted(payments.create(merchant.id(), request.merchantReference(), amount))));
  }

  @PostMapping("/{id}/capture")
  ResponseEntity<JsonNode> capture(
      @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant,
      @RequestHeader(IDEMPOTENCY_KEY) String key,
      @PathVariable UUID id) {
    requireValidKey(key);
    return respond(
        idempotency.execute(
            merchant.id(),
            CAPTURE_SCOPE,
            key,
            new PaymentAction(id),
            () -> accepted(found(id, payments.capture(merchant.id(), id)))));
  }

  @PostMapping("/{id}/void")
  ResponseEntity<JsonNode> voidPayment(
      @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant,
      @RequestHeader(IDEMPOTENCY_KEY) String key,
      @PathVariable UUID id) {
    requireValidKey(key);
    return respond(
        idempotency.execute(
            merchant.id(),
            VOID_SCOPE,
            key,
            new PaymentAction(id),
            () -> accepted(found(id, payments.voidPayment(merchant.id(), id)))));
  }

  @PostMapping("/{id}/refunds")
  ResponseEntity<JsonNode> refund(
      @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant,
      @RequestHeader(IDEMPOTENCY_KEY) String key,
      @PathVariable UUID id,
      @Valid @RequestBody CreateRefundRequest request) {
    requireValidKey(key);
    return respond(
        idempotency.execute(
            merchant.id(),
            REFUND_SCOPE,
            key,
            new RefundAction(id, request),
            () -> {
              Refund refund =
                  payments
                      .refund(
                          merchant.id(),
                          id,
                          request.merchantReference(),
                          request.amountMinor(),
                          request.reason())
                      .orElseThrow(() -> new ResourceNotFoundException("Payment " + id));
              return new IdempotentExecutor.Result(
                  HttpStatus.ACCEPTED.value(),
                  new RefundAcceptedResponse(
                      refund.id(),
                      refund.paymentId(),
                      refund.status().name(),
                      "/v1/refunds/" + refund.id()));
            }));
  }

  /** A refund request for the request hash: the payment it is for is part of it. */
  record RefundAction(UUID paymentId, CreateRefundRequest refund) {}

  /**
   * What a capture or void request is, for the request hash: the same key reused for another
   * payment is a different request (422), not a replay of the first payment's response.
   */
  record PaymentAction(UUID paymentId) {}

  private static Payment found(UUID id, Optional<Payment> payment) {
    return payment.orElseThrow(() -> new ResourceNotFoundException("Payment " + id));
  }

  private static IdempotentExecutor.Result accepted(Payment payment) {
    return new IdempotentExecutor.Result(
        HttpStatus.ACCEPTED.value(),
        new PaymentAcceptedResponse(
            payment.id(), payment.status().name(), "/v1/payments/" + payment.id()));
  }

  private static ResponseEntity<JsonNode> respond(IdempotentResponse response) {
    // The stored body has no trace ID: this response gets the current one, matching X-Trace-Id.
    ObjectNode body = response.body().deepCopy();
    body.put("traceId", TraceIds.current());
    var builder = ResponseEntity.status(response.status());
    if (response.replayed()) {
      builder.header(IdempotentResponse.REPLAYED_HEADER, "true");
    }
    return builder.body(body);
  }

  @GetMapping("/{id}")
  PaymentView get(
      @RequestAttribute(AuthenticatedMerchant.REQUEST_ATTRIBUTE) AuthenticatedMerchant merchant,
      @PathVariable UUID id) {
    return payments
        .find(merchant.id(), id)
        .map(PaymentView::of)
        .orElseThrow(() -> new ResourceNotFoundException("Payment " + id));
  }

  private static void requireValidKey(String key) {
    if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR,
          IDEMPOTENCY_KEY + " must be 1 to " + MAX_KEY_LENGTH + " characters");
    }
  }
}
