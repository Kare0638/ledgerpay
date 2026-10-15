package com.ledgerpay.payment.settlement;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limits on an uploaded settlement report (design §11.1).
 *
 * @param maxFileSize larger uploads are refused with 413 before anything is stored
 * @param maxRows a file with more data rows than this is INVALID
 * @param maxStoredErrors how many line errors are kept and returned; all of them are counted
 */
@ConfigurationProperties("ledgerpay.settlement")
public record SettlementProperties(
    @DefaultValue("10MB") DataSize maxFileSize,
    @DefaultValue("100000") int maxRows,
    @DefaultValue("1000") int maxStoredErrors) {}
