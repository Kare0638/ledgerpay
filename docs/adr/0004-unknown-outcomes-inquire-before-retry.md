# ADR 0004 — Unknown outcomes: inquire before any retry, with fixed request IDs

**Status:** accepted · **Date:** 2026-10-02 · **Issues:** [#7](https://github.com/Kare0638/ledgerpay/issues/7), [#10](https://github.com/Kare0638/ledgerpay/issues/10)

## Context

A call to the PSP can end in three ways: an answer, an explicit rejection, or nothing — a timeout, a dropped connection, a 5xx. The third is the dangerous one. A capture that timed out may have been captured: the PSP committed it and its answer was lost on the way back (`TIMEOUT_AFTER_COMMIT`, design §9.4). Treating that as a failure frees the payment for another capture; treating it as "try again" with a new request can capture twice.

Constraints:

- Money must never move twice, whatever fails and in whatever order (design §2, requirement 1).
- PSP calls happen outside any database transaction (ADR 0002), so a worker can die between the PSP's answer and our commit.
- A PSP may be down for minutes; retries must not hammer it, and must not all return at the same moment.

## Decision

**Every PSP call is a persisted operation with a fixed `psp_request_id`**, written in the same transaction as the business change that needs it. The ID never changes across retries, so the PSP can de-duplicate, and so can we when we ask about it.

**A call that established nothing leaves the outcome unknown**, never failed:

| Situation | Action |
|---|---|
| First attempt | Submit with `psp_request_id` |
| Any later attempt, or the safety-net check of an accepted operation | **Inquire by `psp_request_id` first**; act on what is found; resubmit with the **same** ID only on 404 (the PSP has no record) |
| An accepted operation the PSP no longer knows | Record an error; never resubmit |
| Timeout, connection error, 5xx, 4xx, unreadable answer | Stay PENDING; keep the business state (payment pending, reservation held); record `last_error`; release the lease; retry after a backoff |
| Explicit rejection (FAILED) | Apply it through the money transaction; a rejected capture or void returns the payment to AUTHORIZED |
| 10 attempts that established nothing | Set `needs_review` and log an alert; the operation is no longer claimed; nothing is released or rolled back |

**Backoff:** 1, 2, 4, 8 … seconds, capped at 60 s, with equal jitter: half of the delay is fixed, half random (`RetryBackoff`).

**The money transaction is shared by inquiries and webhooks** (design §9.3), and `UNIQUE (psp_operation_id)` on journals makes a second booking impossible even if two paths race. A worker that dies before the money commit leaves nothing behind, and its lease expires so another attempt inquires. A worker that dies after the commit leaves a final operation, which is never claimed again, and any redelivery of the outcome finds it already applied.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Treat a timeout as a failure | `TIMEOUT_AFTER_COMMIT` shows the PSP may have succeeded. Failing the capture releases the authorisation for a second capture: money moves twice. |
| Retry by resubmitting with a new request ID | The PSP cannot know it is the same capture. |
| Resubmit with the same ID without inquiring first | Safe only if the PSP de-duplicates every operation type perfectly and forever. Inquiring first depends on nothing but a read, and tells us the outcome directly. |
| Retry forever | A permanently broken operation would be retried silently. After 10 attempts a human looks; the business state still says "unknown", which is true. |
| Fixed retry interval | Many operations failing together would return together and hit a recovering PSP at once. |
| Release the reservation or authorisation when retries run out | The outcome is still unknown. Releasing is a decision that needs the inquiry result, or a person. |

## Trade-offs

- A payment can sit in CAPTURE_PENDING for minutes while the PSP is down. That is the honest state: clients poll or wait for the webhook.
- `needs_review` needs someone to act on it. The alert is a log line today; metrics and alert rules come with #22.
- The safety-net check of an accepted operation counts as an attempt, but only attempts that establish nothing can lead to `needs_review`.

## Tests

- `PspOperationWorkerIntegrationTest`: only the first attempt submits; a retry inquires and does not resubmit what the PSP has; it resubmits with the same ID only on 404; 503 and timeouts are unknown; backoff doubles up to a minute; after 10 attempts `needs_review` is set and nothing else changes.
- `RetryBackoffTest`: bounds for every attempt, including the cap.
- `WorkerCrashIntegrationTest` (**AT-13**): killed before the money commit, everything rolls back and the retry inquires and books once; killed after it, a redelivery adds no journal.
- `UnknownOutcomeAcceptanceTest` (**AT-06**, real mock-psp): `TIMEOUT_AFTER_COMMIT` on capture is found by inquiry and booked once, and mock-psp holds a single capture.
