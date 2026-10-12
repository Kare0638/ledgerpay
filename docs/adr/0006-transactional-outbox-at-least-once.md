# ADR 0006 — Transactional outbox; at-least-once delivery with idempotent consumers

**Status:** accepted · **Date:** 2026-10-12 · **Issue:** [#14](https://github.com/Kare0638/ledgerpay/issues/14)

## Context

Every state change of a payment or refund produces a domain event (`PaymentCreated`, `PaymentCaptured`, `RefundSucceeded` …) that notification-service turns into merchant webhooks (design §10).

Constraints:

- A database commit and a Kafka publish cannot share a transaction. Publishing after commit loses the event if the process dies in between; publishing before commit announces a change that may roll back.
- PostgreSQL is the source of truth for money. A broker outage must not stop payments or affect the ledger (design §3, principle 1).
- Merchants act on events in order: `PaymentCaptured` before `RefundSucceeded`. One payment's events must reach the topic in the order they happened.
- Several payment-service instances may run, each with a relay.

## Decision

**The event is written to `outbox_events` in the transaction that makes the change** (`Outbox.append`, `Propagation.MANDATORY`). A rolled-back change leaves no event.

**`OutboxRelay` publishes in one transaction per batch:**

1. Claim up to 100 unpublished events, `ORDER BY seq … FOR UPDATE SKIP LOCKED`, taking **only the earliest unpublished event of each payment**.
2. Send each to `payments.events` with the payment ID as the key, so a payment's events share a partition.
3. Wait for the broker's acknowledgements (at most `publish-timeout`, 15 s).
4. Set `published_at` on the acknowledged events and commit. Unacknowledged ones stay unpublished for the next run.

**A failure is the broker's or the event's.** `EventPublisher.rejectsEvent` tells them apart. For Kafka, only `RecordTooLargeException`, `SerializationException` and `InvalidRecordException` anywhere in the cause chain count as the event's; everything else, timeouts, a missing topic or an authorisation error, could hit every event alike and is the broker's.

- **The broker's:** a send that fails at once (no metadata) stops the batch, so the other events are not each held for another `max.block.ms`. Nothing is counted against any event; they are all tried again on the next run.
- **The event's:** the rest of the batch goes on. The refusal is counted in `rejections`, with `last_error`, and after `max-rejections` (3) the event gets `quarantined_at` and an error log, which is an alert. A quarantined event is no longer claimed, so it stops taking a place in every batch; as it stays unpublished, its payment's later events wait behind it until a human has dealt with it. Before this rule, one event too large for Kafka stopped every batch it led, and so the whole outbox, for good.

**Delivery is at-least-once.** A relay that dies after the broker acknowledged but before its commit leaves the events unpublished, and the next run sends them again. Each message carries the outbox row's `id` as `eventId` (in the envelope and as a header), and consumers de-duplicate on it (#15, `processed_events`).

**Order per payment** comes from two rules:

- `seq`, an identity column (V6), is taken at insert. Every event of an existing payment is written under that payment's row lock, so `seq` follows the order of the changes. `created_at` does not: it is the transaction's *start* time, and a transaction that waited for the lock can carry an earlier time than the one it waited for.
- A payment's next event is not eligible until the previous one is published. A relay that sees an earlier event still unpublished, because another relay holds it, skips that payment for the round; it never sends a later event first. `SKIP LOCKED` still lets relays work on different payments in parallel.

**Producer:** `acks=all` and `enable.idempotence=true`, so an acknowledged event is on every in-sync replica and the producer's own retries do not duplicate it. `max.block.ms`, `request.timeout.ms` and `delivery.timeout.ms` are cut to 5, 5 and 10 s: the relay holds its rows while it waits, so a missing broker must fail in seconds.

**Kafka is not needed to start.** The topic is created at startup when the broker is reachable; otherwise the service starts anyway, and events wait in the table.

**Message:** key = payment ID; value = `{eventId, eventType, paymentId, occurredAt, data}`; headers `eventId`, `eventType`. The `EventPublisher` port keeps the relay independent of Kafka (an SQS adapter is #25).

**The consumer side** (notification-service, #15, 2026-10-12) completes the "idempotent consumers" half:

- An event whose `eventId` is in `processed_events` is skipped. The row is written with the delivery record in one transaction, after the merchant accepted the webhook.
- A failed delivery is retried three times with exponential backoff, then dead-lettered to `payments.events.dlq`; a malformed event is dead-lettered at once. The offset is committed per record once it is handled either way.
- The webhook carries `X-LedgerPay-Event-Id`. A crash after the POST but before the record re-sends it, so merchants de-duplicate too: at-least-once all the way to the merchant, effectively-once where each side de-duplicates.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Publish after commit, from the request thread | Loses the event when the process dies between commit and send, or when Kafka is down. |
| Kafka transactions | Atomic across Kafka topics, not with PostgreSQL. The dual write remains. |
| Change data capture (Debezium on the WAL) | Same guarantee without polling, but another service to run and operate for a demo-scale system. Polling with an index on unpublished rows is cheap at this volume. |
| Order by `created_at` | Transaction start time: two changes to one payment can carry times in the opposite order of their commits. |
| `SKIP LOCKED` alone | Two relays can take two events of one payment, and the one holding the later event may publish it first. |
| A single relay elected by an advisory lock | Correct, but publishing becomes serial across all payments. The earliest-event rule gives the same order per payment and keeps parallelism. |
| Delete published rows | Kept for now: they are the record of what was sent, and the backlog metric needs them. A retention job can prune them later. |

## Trade-offs

- **Duplicates reach consumers** after a relay crash, and after a timeout where the broker had in fact stored the event. Consumers must de-duplicate on `eventId`.
- A payment with many unpublished events needs one relay round per event. Payments have a handful of events, and a round is milliseconds.
- One event the broker keeps refusing holds back the later events of *its* payment only. Other payments are unaffected. Once quarantined it needs a human: publishing that payment's later events without it would deliver them out of order.
- A refusal the classifier does not know is taken as the broker's, which stops the batch on every run. That is the safe side for a broker-wide error, but a new kind of per-record error should be added to `rejectsEvent`.
- The claim transaction stays open while the relay waits for acknowledgements: at most `publish-timeout` with a broker that answers slowly, about `max.block.ms` (5 s) with one that is gone.
- Ordering also depends on every event of a payment being appended under its row lock; a new writer that appends without it could break the order. `PaymentCreated` needs no lock, because no one else can see the payment before it commits.

## Tests

- `OutboxRelayIntegrationTest` (PostgreSQL, recording publisher):
  - a rolled-back payment leaves no outbox row and nothing to publish;
  - events are published once, in `seq` order, and marked published;
  - a payment's next event waits until the earlier one is published;
  - an unacknowledged event stays unpublished and holds back its payment only, and goes out on the next run;
  - a send that fails at once stops the batch;
  - an event refused for what it is does not stop the batch: other payments' events are published, and only its own payment waits;
  - after three refusals the event is quarantined, no longer sent, and later events of other payments go out;
  - a crash between acknowledgement and commit sends the event again with the same id;
  - four concurrent relays over 8 payments × 25 events publish each event exactly once and each payment's events in order. Without the earliest-event rule this test failed in each of three runs; without stopping on an immediate failure, the stop-the-batch test and the unreachable-broker test below failed. Stopping on *every* immediate failure, as before the event/broker distinction, failed the refused-event test here and the too-large test below; claiming quarantined events failed the quarantine test.
- `KafkaOutboxRelayIntegrationTest` (Testcontainers Kafka `apache/kafka:3.9.1`):
  - the message is keyed by payment ID and carries the envelope and headers;
  - **relay restart re-publishes**: a relay killed before marking, then a fresh relay, puts the same `eventId` on the topic twice;
  - with an unreachable broker the relay gives up in under 2.5 s for three events, nothing is marked, and the events are published once Kafka is reachable;
  - an event over the producer's 1 MB `max.request.size` fails with a real `RecordTooLargeException`, another payment's event in the same batch is published, and after three runs the event is quarantined with the exception in `last_error`.
- Both classes also pass with the JVM limited to two CPUs (`taskset -c 0,1`, ADR 0008).
- `NotificationDeliveryIntegrationTest` (Testcontainers Kafka and PostgreSQL, stub merchants): a signed webhook is delivered and recorded and the offset committed; **a duplicate event is delivered once** (without the `processed_events` check this test failed); a merchant that recovers within the retries gets the webhook once; **a merchant that keeps failing sends the event to the DLQ** after the attempt and three retries; a malformed event is dead-lettered and the next one still goes out; a merchant without an endpoint is recorded, not called. `KafkaConfigurationTest` checks that a malformed event is not retried and a failed delivery is.
