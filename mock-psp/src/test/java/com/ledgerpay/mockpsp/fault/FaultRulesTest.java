package com.ledgerpay.mockpsp.fault;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerpay.mockpsp.operation.OperationType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class FaultRulesTest {

  final FaultRules rules = new FaultRules();

  @Test
  void aUseGivenBackCountsAgain() {
    FaultRule rule = rules.add("m_1", OperationType.AUTHORIZE, Fault.DECLINE, 2);

    rules.take("m_1", OperationType.AUTHORIZE).orElseThrow().giveBack();

    assertThat(rules.list()).singleElement().extracting(FaultRule::remaining).isEqualTo(2);
    assertThat(rules.list().getFirst().id()).isEqualTo(rule.id());
  }

  @Test
  void theLastUseGivenBackRestoresTheRuleWhereItStood() {
    rules.add("m_1", OperationType.AUTHORIZE, Fault.DECLINE, 1);
    FaultRule second = rules.add("m_1", null, Fault.DROP_WEBHOOK, null);

    var taken = rules.take("m_1", OperationType.AUTHORIZE).orElseThrow();
    assertThat(rules.list()).containsExactly(second);
    taken.giveBack();
    taken.giveBack();

    assertThat(rules.list()).hasSize(2);
    assertThat(rules.list().getFirst().fault()).isEqualTo(Fault.DECLINE);
    assertThat(rules.list().getFirst().remaining()).isEqualTo(1);
  }

  @Test
  void aRemovedRuleIsNotBroughtBack() {
    FaultRule rule = rules.add("m_1", OperationType.AUTHORIZE, Fault.DECLINE, 1);
    var taken = rules.take("m_1", OperationType.AUTHORIZE).orElseThrow();

    rules.remove(rule.id());
    taken.giveBack();

    assertThat(rules.list()).isEmpty();
  }

  @Test
  void aUseIsGivenBackWhenTheTransactionRollsBack() {
    rules.add("m_1", OperationType.AUTHORIZE, Fault.DECLINE, 1);

    complete(TransactionSynchronization.STATUS_ROLLED_BACK);

    assertThat(rules.list()).singleElement().extracting(FaultRule::remaining).isEqualTo(1);
  }

  @Test
  void aUseIsKeptWhenTheTransactionCommits() {
    rules.add("m_1", OperationType.AUTHORIZE, Fault.DECLINE, 1);

    complete(TransactionSynchronization.STATUS_COMMITTED);

    assertThat(rules.list()).isEmpty();
  }

  /** Takes a use inside a simulated transaction, then completes it with {@code status}. */
  private void complete(int status) {
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(rules.take("m_1", OperationType.AUTHORIZE)).isPresent();
      TransactionSynchronizationUtils.invokeAfterCompletion(
          TransactionSynchronizationManager.getSynchronizations(), status);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }
}
