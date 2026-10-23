package com.ledgerpay.payment.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** What every log line of a request carries (design §13), and that it is gone afterwards. */
class TraceIdFilterTest {

  Map<String, String> during(MockHttpServletRequest request) throws Exception {
    Map<String, String> seen = new HashMap<>();
    new TraceIdFilter()
        .doFilter(
            request,
            new MockHttpServletResponse(),
            (req, res) -> {
              Map<String, String> context = MDC.getCopyOfContextMap();
              if (context != null) {
                seen.putAll(context);
              }
            });
    return seen;
  }

  @Test
  void aPaymentRequestCarriesItsTraceIdIdempotencyKeyAndPayment() throws Exception {
    var request =
        new MockHttpServletRequest(
            "POST", "/v1/payments/0b8e3a52-6c1d-4f4e-9a59-1f1e9c3b2a10/refunds");
    request.addHeader("Idempotency-Key", "order-1-refund-1");
    request.addHeader("Authorization", "Bearer mk_secret_never_logged");

    Map<String, String> mdc = during(request);

    assertThat(mdc)
        .containsEntry("idempotencyKey", "order-1-refund-1")
        .containsEntry("paymentId", "0b8e3a52-6c1d-4f4e-9a59-1f1e9c3b2a10")
        .containsKey("traceId");
    assertThat(mdc.values()).noneMatch(value -> value.contains("mk_secret_never_logged"));
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }

  @Test
  void otherPathsCarryOnlyTheTraceId() throws Exception {
    Map<String, String> mdc = during(new MockHttpServletRequest("GET", "/v1/accounts/x/balance"));

    assertThat(mdc).containsOnlyKeys("traceId");
  }
}
