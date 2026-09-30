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

  public Optional<Payment> find(String merchantId, UUID id) {
    return payments.find(merchantId, id);
  }
}
