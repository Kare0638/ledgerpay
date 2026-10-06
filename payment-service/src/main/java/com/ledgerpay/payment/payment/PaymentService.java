package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;
import com.ledgerpay.payment.outbox.Outbox;
import com.ledgerpay.payment.psp.PspOperationType;
import com.ledgerpay.payment.psp.PspOperations;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

  public static final String PAYMENT_CREATED = "PaymentCreated";

  private final Payments payments;
  private final Refunds refunds;
  private final PspOperations pspOperations;
  private final Outbox outbox;

  public PaymentService(
      Payments payments, Refunds refunds, PspOperations pspOperations, Outbox outbox) {
    this.payments = payments;
    this.refunds = refunds;
    this.pspOperations = pspOperations;
    this.outbox = outbox;
  }

  /**
   * Creates a payment and its pending AUTHORIZE operation — the business layer of design §8. The
   * same merchant reference with the same amount returns the existing payment; with a different
   * amount it is 409 {@code DUPLICATE_REFERENCE}. No PSP call happens here: the worker makes it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Payment create(String merchantId, String merchantReference, Money amount) {
    UUID id = UUID.randomUUID();
    if (!payments.insertIfReferenceFree(id, merchantId, merchantReference, amount)) {
      Payment existing = payments.findByReference(merchantId, merchantReference).orElseThrow();
      if (!existing.amount().equals(amount)) {
        throw new DuplicateReferenceException(merchantReference);
      }
      return existing;
    }
    pspOperations.createPending(id, PspOperationType.AUTHORIZE, amount);
    outbox.append(
        id,
        PAYMENT_CREATED,
        Map.of(
            "paymentId",
            id,
            "merchantId",
            merchantId,
            "merchantReference",
            merchantReference,
            "amountMinor",
            amount.minor(),
            "currency",
            amount.currency().getCurrencyCode(),
            "status",
            PaymentStatus.AUTH_PENDING.name()));
    return payments.find(merchantId, id).orElseThrow();
  }

  /**
   * Starts a capture of the full authorised amount (partial capture is out of scope): AUTHORIZED
   * becomes CAPTURE_PENDING and a pending CAPTURE operation is written. The payment is locked, so
   * of two concurrent captures one proceeds and the other finds CAPTURE_PENDING and gets 409.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Payment> capture(String merchantId, UUID id) {
    return request(merchantId, id, PaymentStatus.CAPTURE_PENDING, PspOperationType.CAPTURE);
  }

  /** Starts releasing the authorisation: AUTHORIZED becomes VOID_PENDING. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Payment> voidPayment(String merchantId, UUID id) {
    return request(merchantId, id, PaymentStatus.VOID_PENDING, PspOperationType.VOID);
  }

  private Optional<Payment> request(
      String merchantId, UUID id, PaymentStatus target, PspOperationType type) {
    Optional<Payment> locked = payments.lock(merchantId, id);
    if (locked.isEmpty()) {
      return Optional.empty();
    }
    Payment payment = locked.get();
    payments.update(id, payment.status().transitionTo(target), payment.captured());
    pspOperations.createPending(id, type, payment.amount());
    return payments.find(merchantId, id);
  }

  /**
   * Accepts a partial or full refund (design §5.4). The payment is locked first, so the refundable
   * amount read here cannot change before the reservation is written: of two concurrent refunds
   * that together exceed it, the second waits for the first and then gets 409 {@code
   * REFUND_AMOUNT_EXCEEDED}. The reservation holds the amount until the PSP answers; no PSP call
   * happens here.
   *
   * <p>A merchant reference already used for the same refund returns that refund, before any amount
   * check: a retry must not be refused because its own reservation used up the balance. Used for
   * anything else, it is 409 {@code DUPLICATE_REFERENCE}.
   *
   * @param amountMinor in the payment's currency
   * @return empty if the payment does not exist or belongs to another merchant
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Refund> refund(
      String merchantId,
      UUID paymentId,
      String merchantReference,
      long amountMinor,
      String reason) {
    Optional<Payment> locked = payments.lock(merchantId, paymentId);
    if (locked.isEmpty()) {
      return Optional.empty();
    }
    Payment payment = locked.get();
    Money amount = Money.positive(amountMinor, payment.amount().currency());
    Optional<Refund> existing = refunds.findByReference(merchantId, merchantReference);
    if (existing.isPresent()) {
      return Optional.of(sameRefundOrConflict(existing.get(), paymentId, amount));
    }
    if (payment.status() != PaymentStatus.CAPTURED) {
      throw new ApiException(
          ErrorCode.INVALID_STATE,
          "Payment in status " + payment.status() + " cannot be refunded; it must be CAPTURED");
    }
    if (payment.refundable().minus(amount).isNegative()) {
      throw new RefundAmountExceededException(amount, payment.refundable());
    }
    UUID refundId = UUID.randomUUID();
    if (!refunds.insertIfReferenceFree(
        refundId, paymentId, merchantId, merchantReference, amount, reason)) {
      // Taken meanwhile by a refund of another payment, whose lock we do not hold.
      Refund taken = refunds.findByReference(merchantId, merchantReference).orElseThrow();
      return Optional.of(sameRefundOrConflict(taken, paymentId, amount));
    }
    payments.reserveRefund(paymentId, amount);
    pspOperations.createPending(paymentId, refundId, PspOperationType.REFUND, amount);
    return refunds.find(merchantId, refundId);
  }

  private static Refund sameRefundOrConflict(Refund existing, UUID paymentId, Money amount) {
    if (!existing.paymentId().equals(paymentId) || !existing.amount().equals(amount)) {
      throw new DuplicateReferenceException(existing.merchantReference(), "refund");
    }
    return existing;
  }

  public Optional<Payment> find(String merchantId, UUID id) {
    return payments.find(merchantId, id);
  }

  public Optional<Refund> findRefund(String merchantId, UUID id) {
    return refunds.find(merchantId, id);
  }
}
