package com.ledgerpay.payment.payment;

import static com.ledgerpay.payment.payment.PaymentStatus.AUTHORIZED;
import static com.ledgerpay.payment.payment.PaymentStatus.AUTH_PENDING;
import static com.ledgerpay.payment.payment.PaymentStatus.CAPTURED;
import static com.ledgerpay.payment.payment.PaymentStatus.CAPTURE_PENDING;
import static com.ledgerpay.payment.payment.PaymentStatus.DECLINED;
import static com.ledgerpay.payment.payment.PaymentStatus.VOIDED;
import static com.ledgerpay.payment.payment.PaymentStatus.VOID_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerpay.payment.api.ErrorCode;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class PaymentStatusTest {

  /** The transition table from design §5.3, written out independently of the implementation. */
  static final Map<PaymentStatus, Set<PaymentStatus>> LEGAL =
      Map.of(
          AUTH_PENDING, Set.of(AUTHORIZED, DECLINED),
          AUTHORIZED, Set.of(CAPTURE_PENDING, VOID_PENDING),
          CAPTURE_PENDING, Set.of(CAPTURED, AUTHORIZED),
          VOID_PENDING, Set.of(VOIDED, AUTHORIZED),
          DECLINED, Set.of(),
          CAPTURED, Set.of(),
          VOIDED, Set.of());

  /** All 7 × 7 = 49 (from, to) pairs, each with whether design §5.3 allows it. */
  static Stream<Arguments> everyPair() {
    return Arrays.stream(PaymentStatus.values())
        .flatMap(
            from ->
                Arrays.stream(PaymentStatus.values())
                    .map(to -> Arguments.of(from, to, LEGAL.get(from).contains(to))));
  }

  @ParameterizedTest(name = "{0} -> {1}: {2}")
  @MethodSource("everyPair")
  void canTransitionToMatchesTheDesign(PaymentStatus from, PaymentStatus to, boolean legal) {
    assertThat(from.canTransitionTo(to)).isEqualTo(legal);
  }

  @ParameterizedTest(name = "{0} -> {1}: {2}")
  @MethodSource("everyPair")
  void transitionToReturnsTheTargetOrFailsWithInvalidState(
      PaymentStatus from, PaymentStatus to, boolean legal) {
    if (legal) {
      assertThat(from.transitionTo(to)).isEqualTo(to);
    } else {
      assertThatThrownBy(() -> from.transitionTo(to))
          .isInstanceOfSatisfying(
              InvalidStateException.class,
              e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE);
                assertThat(e.code().status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.from()).isEqualTo(from);
                assertThat(e.to()).isEqualTo(to);
              });
    }
  }

  @Test
  void tableCoversEveryStatus() {
    assertThat(LEGAL).containsOnlyKeys(PaymentStatus.values());
  }

  @Test
  void thereAreExactlyEightLegalTransitions() {
    long legal = everyPair().filter(pair -> (boolean) pair.get()[2]).count();

    assertThat(legal).isEqualTo(8);
  }

  @Test
  void finalStatusesHaveNoWayOut() {
    for (PaymentStatus to : PaymentStatus.values()) {
      assertThat(DECLINED.canTransitionTo(to)).isFalse();
      assertThat(CAPTURED.canTransitionTo(to)).isFalse();
      assertThat(VOIDED.canTransitionTo(to)).isFalse();
    }
  }

  @Test
  void anExplicitRejectionReturnsToAuthorized() {
    assertThat(CAPTURE_PENDING.transitionTo(AUTHORIZED)).isEqualTo(AUTHORIZED);
    assertThat(VOID_PENDING.transitionTo(AUTHORIZED)).isEqualTo(AUTHORIZED);
  }

  @Test
  void aConfirmedOutcomeCannotGoBackToPending() {
    assertThat(CAPTURED.canTransitionTo(CAPTURE_PENDING)).isFalse();
    assertThat(AUTHORIZED.canTransitionTo(AUTH_PENDING)).isFalse();
    assertThat(VOIDED.canTransitionTo(VOID_PENDING)).isFalse();
  }
}
