package com.ledgerpay.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unknown outcomes against the real mock-psp (design §9.1, §12). AT-06: the PSP captures, but its
 * answer never reaches us. The capture must be found by inquiry and booked once, with a single
 * capture at the PSP: a timeout is never a failure, and never a reason to submit again blindly.
 */
class UnknownOutcomeAcceptanceTest extends AcceptanceTestSupport {

  @Test
  void at06TimeoutAfterCommitOnCaptureIsFoundByInquiryAndBookedOnce() throws Exception {
    UUID paymentId = authorised();
    LiveStack.FAULTS.inject(merchant, "CAPTURE", "TIMEOUT_AFTER_COMMIT", 1);

    post("/v1/payments/" + paymentId + "/capture", null);
    worker.drain(); // the submit reaches mock-psp, which commits it and withholds the answer

    Map<String, Object> afterTimeout =
        jdbc.sql(
                """
                SELECT status, attempts, last_error, psp_reference FROM psp_operations
                 WHERE payment_id = ? AND type = 'CAPTURE'""")
            .param(paymentId)
            .query()
            .singleRow();
    assertThat(afterTimeout.get("status")).isEqualTo("PENDING");
    assertThat(afterTimeout.get("attempts")).isEqualTo(1);
    assertThat(afterTimeout.get("psp_reference")).isNull();
    assertThat((String) afterTimeout.get("last_error")).startsWith("submit:");
    assertThat(status(paymentId)).isEqualTo("CAPTURE_PENDING");

    // The retry is due (skipping the backoff): it inquires, finds the capture and books it. The
    // webhook mock-psp sent meanwhile is processed afterwards and finds the work done.
    jdbc.sql(
            """
            UPDATE psp_operations SET next_attempt_at = now() - interval '1 second'
             WHERE payment_id = ? AND type = 'CAPTURE'""")
        .param(paymentId)
        .update();
    worker.drain();
    assertThat(status(paymentId)).as("booked by the inquiry").isEqualTo("CAPTURED");
    inbox.processAll();

    assertThat(journals(paymentId)).isEqualTo(1);
    assertThat(capturedMinor(paymentId)).isEqualTo(10_000);
    // mock-psp's own records: exactly one capture, succeeded.
    assertThat(
            mockPsp
                .sql("SELECT status FROM operations WHERE merchant_id = ? AND type = 'CAPTURE'")
                .param(merchant)
                .query(String.class)
                .list())
        .containsExactly("SUCCEEDED");
  }
}
