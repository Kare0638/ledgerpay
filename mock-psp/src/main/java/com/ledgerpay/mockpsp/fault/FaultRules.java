package com.ledgerpay.mockpsp.fault;

import com.ledgerpay.mockpsp.operation.OperationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fault rules, held in memory: they exist only for tests and demos, and can only be added through
 * the dev-profile endpoint, so outside it this is always empty and every operation behaves.
 */
@Component
public class FaultRules {

  private final List<FaultRule> rules = new ArrayList<>();

  public synchronized FaultRule add(
      String merchantId, OperationType type, Fault fault, Integer times) {
    FaultRule rule = new FaultRule(UUID.randomUUID(), merchantId, type, fault, times);
    rules.add(rule);
    return rule;
  }

  /** The fault for a new operation, if a rule matches; the first match is used up by one. */
  public synchronized Optional<Fault> take(String merchantId, OperationType type) {
    for (int i = 0; i < rules.size(); i++) {
      FaultRule rule = rules.get(i);
      if (!rule.matches(merchantId, type)) {
        continue;
      }
      if (rule.remaining() != null) {
        if (rule.remaining() <= 1) {
          rules.remove(i);
        } else {
          rules.set(
              i,
              new FaultRule(
                  rule.id(), rule.merchantId(), rule.type(), rule.fault(), rule.remaining() - 1));
        }
      }
      return Optional.of(rule.fault());
    }
    return Optional.empty();
  }

  public synchronized List<FaultRule> list() {
    return List.copyOf(rules);
  }

  public synchronized boolean remove(UUID id) {
    return rules.removeIf(rule -> rule.id().equals(id));
  }

  public synchronized void clear() {
    rules.clear();
  }
}
