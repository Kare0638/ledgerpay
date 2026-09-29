package com.ledgerpay.payment.ledger;

/**
 * The PSP operations that move money and therefore produce a journal. Authorisation and void have
 * no entry type: they move no money (ADR 0003).
 */
public enum EntryType {
  CAPTURE,
  REFUND
}
