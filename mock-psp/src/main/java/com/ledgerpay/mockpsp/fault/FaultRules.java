package com.ledgerpay.mockpsp.fault;

import com.ledgerpay.mockpsp.operation.OperationType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Fault rules, held in memory: they exist only for tests and demos, and can only be added through
 * the dev-profile endpoint, so outside it this is always empty and every operation behaves.
 */
@Component
public class FaultRules {

  /**
   * One use of a rule, taken for an operation that may not end up being created. {@link
   * #giveBack()} returns the use to the rule, at most once.
   */
  public final class Taken {
    private final FaultRule rule;
    private final AtomicBoolean givenBack = new AtomicBoolean();

    private Taken(FaultRule rule) {
      this.rule = rule;
    }

    public Fault fault() {
      return rule.fault();
    }

    public void giveBack() {
      if (givenBack.compareAndSet(false, true)) {
        restore(rule);
      }
    }
  }

  private final List<FaultRule> rules = new ArrayList<>();

  /** Rules used up by {@link #take}, by ID, with where they stood, until given back or cleared. */
  private final Map<UUID, Integer> exhausted = new HashMap<>();

  public synchronized FaultRule add(
      String merchantId, OperationType type, Fault fault, Integer times) {
    FaultRule rule = new FaultRule(UUID.randomUUID(), merchantId, type, fault, times);
    rules.add(rule);
    return rule;
  }

  /**
   * The fault for a new operation, if a rule matches; the first match is used up by one. Inside a
   * transaction the use is given back if the transaction does not commit.
   */
  public synchronized Optional<Taken> take(String merchantId, OperationType type) {
    for (int i = 0; i < rules.size(); i++) {
      FaultRule rule = rules.get(i);
      if (!rule.matches(merchantId, type)) {
        continue;
      }
      if (rule.remaining() != null) {
        if (rule.remaining() <= 1) {
          rules.remove(i);
          exhausted.put(rule.id(), i);
        } else {
          rules.set(i, withRemaining(rule, rule.remaining() - 1));
        }
      }
      Taken taken = new Taken(rule);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                  taken.giveBack();
                }
              }
            });
      }
      return Optional.of(taken);
    }
    return Optional.empty();
  }

  /** Undoes one {@link #take} of {@code rule}, unless the rule has since been removed. */
  private synchronized void restore(FaultRule rule) {
    if (rule.remaining() == null) {
      return;
    }
    for (int i = 0; i < rules.size(); i++) {
      FaultRule current = rules.get(i);
      if (current.id().equals(rule.id())) {
        rules.set(i, withRemaining(current, current.remaining() + 1));
        return;
      }
    }
    Integer position = exhausted.remove(rule.id());
    if (position != null) {
      rules.add(Math.min(position, rules.size()), withRemaining(rule, 1));
    }
  }

  private static FaultRule withRemaining(FaultRule rule, int remaining) {
    return new FaultRule(rule.id(), rule.merchantId(), rule.type(), rule.fault(), remaining);
  }

  public synchronized List<FaultRule> list() {
    return List.copyOf(rules);
  }

  public synchronized boolean remove(UUID id) {
    exhausted.remove(id);
    return rules.removeIf(rule -> rule.id().equals(id));
  }

  public synchronized void clear() {
    rules.clear();
    exhausted.clear();
  }
}
