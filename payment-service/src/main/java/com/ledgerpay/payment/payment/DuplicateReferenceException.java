package com.ledgerpay.payment.payment;

import com.ledgerpay.payment.api.ApiException;
import com.ledgerpay.payment.api.ErrorCode;

/** The merchant reference is already used by a payment with different parameters. */
public class DuplicateReferenceException extends ApiException {

  public DuplicateReferenceException(String merchantReference) {
    super(
        ErrorCode.DUPLICATE_REFERENCE,
        "merchantReference " + merchantReference + " is already used by a different payment");
  }
}
