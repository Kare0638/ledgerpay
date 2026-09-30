package com.ledgerpay.payment.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerpay.payment.api.CreatePaymentRequest;
import org.junit.jupiter.api.Test;

class RequestHashTest {

  @Test
  void sameRequestSameHash() {
    assertThat(RequestHash.of(new CreatePaymentRequest("order-1", 10000L, "GBP")))
        .isEqualTo(RequestHash.of(new CreatePaymentRequest("order-1", 10000L, "GBP")))
        .hasSize(64);
  }

  @Test
  void anyFieldChangesTheHash() {
    String base = RequestHash.of(new CreatePaymentRequest("order-1", 10000L, "GBP"));

    assertThat(RequestHash.of(new CreatePaymentRequest("order-2", 10000L, "GBP")))
        .isNotEqualTo(base);
    assertThat(RequestHash.of(new CreatePaymentRequest("order-1", 12000L, "GBP")))
        .isNotEqualTo(base);
  }
}
