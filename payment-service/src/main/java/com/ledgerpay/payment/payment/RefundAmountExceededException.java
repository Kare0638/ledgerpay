package com.ledgerpay.payment.payment;

import com.ledgerpay.common.money.Money;
import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;

/** A refund larger than what is left: captured − refunded − reserved (design §5.4). */
public class RefundAmountExceededException extends ApiException {

  public RefundAmountExceededException(Money requested, Money refundable) {
    super(
        ErrorCode.REFUND_AMOUNT_EXCEEDED,
        "Refund of "
            + requested.minor()
            + " exceeds the refundable amount of "
            + refundable.minor());
  }
}
