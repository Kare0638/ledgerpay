package com.ledgerpay.mockpsp.fault;

import com.ledgerpay.mockpsp.operation.OperationType;
import java.util.UUID;

/**
 * Applies {@code fault} to new operations of {@code merchantId} of the given type, or of any type
 * if none is given, for the next {@code remaining} operations, or for all if null.
 */
public record FaultRule(
    UUID id, String merchantId, OperationType type, Fault fault, Integer remaining) {

  boolean matches(String merchant, OperationType operationType) {
    return merchantId.equals(merchant) && (type == null || type == operationType);
  }
}
