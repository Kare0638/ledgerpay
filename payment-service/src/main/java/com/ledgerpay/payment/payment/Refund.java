package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;
import java.util.UUID;

/** A partial or full refund of a captured payment (design §5.4). */
public record Refund(
    UUID id,
    UUID paymentId,
    String merchantId,
    String merchantReference,
    Money amount,
    RefundStatus status,
    String reason) {}
