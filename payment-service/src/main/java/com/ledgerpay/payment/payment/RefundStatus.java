package com.ledgerpay.payment.payment;

/** A refund is PENDING until the PSP confirms it; the two final states never change. */
public enum RefundStatus {
  PENDING,
  SUCCEEDED,
  FAILED
}
