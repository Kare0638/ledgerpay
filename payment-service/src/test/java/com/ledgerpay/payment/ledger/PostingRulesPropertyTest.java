package com.ledgerpay.payment.ledger;

import static com.ledgerpay.common.money.Money.GBP;
import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerpay.common.money.Money;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class PostingRulesPropertyTest {

  @Property(tries = 5_000)
  void everyCaptureJournalIsBalanced(
      @ForAll @LongRange(min = 1, max = 100_000_000) long amount,
      @ForAll @IntRange(min = 0, max = 10_000) int bps) {
    List<Posting> postings = PostingRules.capture("mock-psp", "m_1", Money.of(amount, GBP), bps);

    assertBalanced(postings);
    assertThat(postings.get(0))
        .isEqualTo(new Posting(Account.pspReceivable("mock-psp"), Money.of(amount, GBP)));
  }

  @Property(tries = 5_000)
  void everyRefundJournalIsBalanced(@ForAll @LongRange(min = 1, max = 100_000_000) long amount) {
    assertBalanced(PostingRules.refund("mock-psp", "m_1", Money.of(amount, GBP)));
  }

  static void assertBalanced(List<Posting> postings) {
    assertThat(postings).hasSizeGreaterThanOrEqualTo(2);
    assertThat(postings.stream().mapToLong(p -> p.amount().minor()).sum()).isZero();
  }
}
