package com.ledgerpay.payment.settlement;

import java.time.Instant;

/** One valid row of a settlement report, with the file line it came from (design §11.1). */
public record SettlementRow(
    int lineNo,
    String pspReference,
    String pspRequestId,
    String txnType,
    String merchantId,
    long amountMinor,
    String currency,
    Instant succeededAt) {}
