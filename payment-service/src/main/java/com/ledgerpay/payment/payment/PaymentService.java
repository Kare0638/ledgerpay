package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;
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
  private final PspOperations pspOperations;
  private final Outbox outbox;

  public PaymentService(Payments payments, PspOperations pspOperations, Outbox outbox) {
    this.payments = payments;
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

  public Optional<Payment> find(String merchantId, UUID id) {
    return payments.find(merchantId, id);
  }
}
