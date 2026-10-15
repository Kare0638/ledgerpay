package com.ledgerpay.payment.settlement;

/**
 * Why a settlement report is INVALID: a line of the file (from 1, the header), the column when one
 * is to blame, and what is wrong.
 */
public record LineError(int line, String column, String message) {}
