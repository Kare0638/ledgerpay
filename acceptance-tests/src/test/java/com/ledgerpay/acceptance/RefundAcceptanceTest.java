package com.ledgerpay.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Partial refunds against the real mock-psp (design §5.4, §12). AT-10 runs the whole flow; AT-12
 * makes mock-psp withhold a refund's answer, then decline one, and checks what the reservation does
 * in each case.
 */
class RefundAcceptanceTest extends AcceptanceTestSupport {

  UUID captured() throws Exception {
    UUID paymentId = authorised();
    post("/v1/payments/" + paymentId + "/capture", null);
    until(paymentId, "CAPTURED");
    return paymentId;
  }

  UUID refund(UUID paymentId, long amount) throws Exception {
    JsonNode accepted =
        post(
            "/v1/payments/" + paymentId + "/refunds",
            """
            {"merchantReference": "refund-%s", "amountMinor": %d, "reason": "returned"}"""
                .formatted(UUID.randomUUID(), amount));
    return UUID.fromString(accepted.path("refundId").asText());
  }

  Map<String, Object> amounts(UUID paymentId) {
    return jdbc.sql("SELECT refunded_minor, refund_reserved_minor FROM payments WHERE id = ?")
        .param(paymentId)
        .query()
        .singleRow();
  }

  String refundStatus(UUID refundId) {
    return jdbc.sql("SELECT status FROM refunds WHERE id = ?")
        .param(refundId)
        .query(String.class)
        .single();
  }

  /** Runs the worker and the inbox until the refund has a final status. */
  void untilFinal(UUID refundId) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(200))
        .until(
            () -> {
              worker.drain();
              inbox.processAll();
              return !refundStatus(refundId).equals("PENDING");
            });
  }

  long refundJournals(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM journal_entries j JOIN psp_operations o ON o.id = j.psp_operation_id
             WHERE o.payment_id = ? AND o.type = 'REFUND'""")
        .param(paymentId)
        .query(Long.class)
        .single();
  }

  List<String> pspRefunds() {
    return mockPsp
        .sql(
            "SELECT status FROM operations WHERE merchant_id = ? AND type = 'REFUND' ORDER BY created_at")
        .param(merchant)
        .query(String.class)
        .list();
  }

  /** AT-10: £100 refunded as £30 then £70. */
  @Test
  void at10ThirtyThenSeventyRefundsInFull() throws Exception {
    UUID paymentId = captured();

    UUID first = refund(paymentId, 3_000);
    untilFinal(first);
    UUID second = refund(paymentId, 7_000);
    untilFinal(second);

    assertThat(refundStatus(first)).isEqualTo("SUCCEEDED");
    assertThat(refundStatus(second)).isEqualTo("SUCCEEDED");
    assertThat(amounts(paymentId))
        .containsEntry("refunded_minor", 10_000L)
        .containsEntry("refund_reserved_minor", 0L);
    assertThat(refundJournals(paymentId)).isEqualTo(2);
    assertThat(
            jdbc.sql(
                    """
                    SELECT coalesce(sum(p.amount_minor), 0) FROM postings p
                      JOIN journal_entries j ON j.id = p.entry_id
                      JOIN psp_operations o ON o.id = j.psp_operation_id
                     WHERE o.payment_id = ? AND p.account_id = 'psp_receivable:mock-psp'""")
                .param(paymentId)
                .query(Long.class)
                .single())
        .as("receivable")
        .isZero();
    assertThat(pspRefunds()).containsExactly("SUCCEEDED", "SUCCEEDED");
  }

  /**
   * AT-12: a refund's answer is withheld, so the outcome is unknown and the reservation stays; the
   * inquiry then finds it succeeded and books it once. A declined refund releases its reservation
   * and books nothing.
   */
  @Test
  void at12TimeoutKeepsTheReservationSuccessBooksOnceAndFailureReleases() throws Exception {
    UUID paymentId = captured();
    LiveStack.FAULTS.inject(merchant, "REFUND", "TIMEOUT_AFTER_COMMIT", 1);

    UUID timedOut = refund(paymentId, 4_000);
    worker.drain(); // mock-psp commits the refund and withholds the answer past our timeout

    assertThat(refundStatus(timedOut)).isEqualTo("PENDING");
    assertThat(amounts(paymentId))
        .as("unknown keeps the reservation")
        .containsEntry("refunded_minor", 0L)
        .containsEntry("refund_reserved_minor", 4_000L);

    // The retry is due (skipping the backoff): it inquires and finds the refund succeeded.
    jdbc.sql(
            """
            UPDATE psp_operations SET next_attempt_at = now() - interval '1 second'
             WHERE refund_id = ?""")
        .param(timedOut)
        .update();
    untilFinal(timedOut);
    inbox.processAll(); // the webhook sent meanwhile finds the work done

    assertThat(refundStatus(timedOut)).isEqualTo("SUCCEEDED");
    assertThat(amounts(paymentId))
        .containsEntry("refunded_minor", 4_000L)
        .containsEntry("refund_reserved_minor", 0L);
    assertThat(refundJournals(paymentId)).isEqualTo(1);

    LiveStack.FAULTS.inject(merchant, "REFUND", "DECLINE", 1);
    UUID declined = refund(paymentId, 6_000);
    untilFinal(declined);

    assertThat(refundStatus(declined)).isEqualTo("FAILED");
    assertThat(amounts(paymentId))
        .as("failure releases only")
        .containsEntry("refunded_minor", 4_000L)
        .containsEntry("refund_reserved_minor", 0L);
    assertThat(refundJournals(paymentId)).isEqualTo(1);
    assertThat(pspRefunds()).containsExactly("SUCCEEDED", "FAILED");
  }
}
