# LedgerPay

A payment, double-entry ledger and settlement reconciliation service in Java 21 and Spring Boot, built around the failure modes that matter in payments: client retries, PSP timeouts with unknown outcomes, lost or duplicated webhooks, concurrent refunds, and books that disagree with the PSP's settlement report.

> **Status (October 2026):** the core payment flow with partial refunds, the ledger, failure handling around the PSP and the event pipeline to signed merchant webhooks are built and tested, and the whole flow runs with one command ([quick start](#quick-start)); reconciliation is next. Progress is tracked on the [milestones](https://github.com/Kare0638/ledgerpay/milestones).

## Built and tested

- **Merchant isolation**: API keys stored as SHA-256 hashes; the merchant always comes from the key. Another merchant's payments, refunds and balance are the same 404 as something that does not exist (AT-14). Operations endpoints take a separate key, which an operator uses to have the worker inquire again about an operation set aside for review.
- **Idempotent payments API**: `Idempotency-Key` plus database unique constraints at three layers: request, merchant reference and journal ([ADR 0002](docs/adr/0002-idempotency-via-database-unique-constraints.md)). Fifty concurrent creates with one key make one payment (AT-02).
- **Authorise, capture, void and refund** against a simulated PSP. Every money operation is persisted first, called outside any transaction by a worker using `SKIP LOCKED` and leases, and confirmed asynchronously.
- **Unknown outcomes handled safely**: a timeout is never treated as a failure. Every retry inquires by a fixed request ID before it may resubmit, with exponential backoff. After 10 consecutive failures the operation is set aside for review, and the payment stays pending ([ADR 0004](docs/adr/0004-unknown-outcomes-inquire-before-retry.md)). A capture whose response is lost is found by inquiry and booked once (AT-06); a worker killed before or after the money commit never books twice (AT-13).
- **Partial refunds with a transactional reservation**: accepting a refund locks the payment, checks captured − refunded − reserved and reserves the amount in one transaction. Success releases the reservation into a refund journal, failure releases it only, and an unknown outcome keeps it ([ADR 0005](docs/adr/0005-refund-reservation-with-pessimistic-locking.md)). Concurrent refunds over the limit get one 409 (AT-11), and a refund timed out by the PSP is booked once by inquiry (AT-12).
- **Double-entry ledger with database-enforced invariants**: a deferred balance constraint, append-only journals and exactly one journal per settled operation ([ADR 0001](docs/adr/0001-money-as-integer-minor-units.md), [ADR 0003](docs/adr/0003-no-postings-on-authorisation-or-void.md)). Property-based tests with jqwik cover fees and posting rules.
- **Signed webhooks through a persisted inbox**: HMAC with a timestamp window. Events are de-duplicated by event ID and by outcome. Stale events are ignored. Unknown, mismatched or contradicting events are quarantined with the reason, and conflicting bodies are kept for investigation (AT-07, AT-08, AT-09).
- **Transactional outbox to Kafka**: every event is written in the transaction that makes the change, so a rollback leaves none. A relay publishes to `payments.events` keyed by payment ID (`acks=all`, idempotent producer) and marks events only after the broker acknowledges: at-least-once, with the outbox ID as `eventId` for consumers to de-duplicate. A payment's next event waits until the previous one is published, so concurrent relays cannot reorder it. With Kafka down, payments carry on and events wait in the table ([ADR 0006](docs/adr/0006-transactional-outbox-at-least-once.md)).
- **Signed merchant webhooks from Kafka**: notification-service consumes `payments.events`, skips event IDs it has processed, and POSTs the event to the merchant's endpoint, signed with a per-merchant secret it generates at registration. A failing merchant is retried three times with exponential backoff, then the event goes to `payments.events.dlq`; a malformed event goes there at once ([ADR 0006](docs/adr/0006-transactional-outbox-at-least-once.md)).
- **A mock PSP with fault injection**: decline, timeout after commit, dropped, duplicated, out-of-order and delayed webhooks, used by acceptance tests that run payment-service against the real mock-psp process and PostgreSQL.
- **CI quality gate**: every push runs the unit, Testcontainers and acceptance tests, and fails below 80 % line coverage in any module ([below](#running-the-tests)).
- **Performance analysis**: a JMH benchmark of PSP calls on virtual threads against platform thread pools, and JFR recordings under k6 load ([below](#performance)).

Acceptance tests from the [design's list](docs/design.md): AT-01 to AT-14 pass, 14 of 19.

## Planned

| Milestone | What | Issues |
|---|---|---|
| M3: reconciliation and observability | Settlement CSV import, matching on a consistent snapshot with classified breaks, ledger integrity check, demo data | #17–#21 |
| | Metrics, Grafana dashboard, alert rules, structured logs | #22 |
| M4: performance and cloud | Published k6 results, Terraform for ECS Fargate and RDS | #23, #24 |
| Optional | An LLM assistant that drafts analyses of reconciliation breaks; SQS/SNS adapter | #26, #25 |

## Architecture

```
Merchant ──▶ payment-service ──▶ PostgreSQL (source of truth for money)
                 │    ▲
      PSP calls  │    │  signed webhooks (settlement CSV: planned)
                 ▼    │
               mock-psp (own state, fault injection)

payment-service ──outbox relay──▶ Kafka payments.events ──▶ notification-service ──▶ signed merchant webhooks
                                                                   └─ after 3 retries ──▶ payments.events.dlq
```

See [docs/design.md](docs/design.md) for the full design: state machine, posting rules, schema, API, recovery paths, reconciliation rules and the acceptance test list.

## Tech stack

Java 21 · Spring Boot 3 · PostgreSQL 16 · Flyway · Apache Kafka · Testcontainers · jqwik · JMH · JFR · k6 · Docker Compose

Planned: Micrometer / Prometheus / Grafana · Terraform · AWS (ECS Fargate, RDS)

## Quick start

Prerequisites: Docker with Compose v2, bash and curl. JDK 21 only to run the tests outside Docker.

```bash
git clone https://github.com/Kare0638/ledgerpay.git && cd ledgerpay
scripts/demo.sh
```

[`scripts/demo.sh`](scripts/demo.sh) builds and starts the stack, seeds merchant `m_demo` with a new API key, registers its webhook endpoint, then pays £100, captures it and refunds £30. At each step it checks the money effect, not just the HTTP status:

- the ledger has two balanced journals for the payment;
- the merchant payable moved by −6900 (−9900 on capture, with the 1 % fee kept, then +3000 on refund);
- every event reached Kafka through the outbox;
- every event was delivered to the merchant as a signed webhook.

It stops with `FAILED` on the first mismatch. The first build downloads Maven dependencies and takes a few minutes. `scripts/demo.sh --fresh` first deletes the stack's volumes, for a database left by an older checkout.

### The same flow by hand

With the stack up, `KEY` set to the key the demo printed and `PAYMENT` to the `paymentId` the first call returns:

```bash
curl -s -X POST localhost:8080/v1/payments -H "Authorization: Bearer $KEY" \
  -H 'Idempotency-Key: order-1' -H 'Content-Type: application/json' \
  -d '{"merchantReference":"order-1","amountMinor":10000,"currency":"GBP"}'
# 202 {"paymentId":"…","status":"AUTH_PENDING",…}; mock-psp authorises it by webhook within a second

curl -s localhost:8080/v1/payments/$PAYMENT -H "Authorization: Bearer $KEY"          # AUTHORIZED
curl -s -X POST localhost:8080/v1/payments/$PAYMENT/capture \
  -H "Authorization: Bearer $KEY" -H 'Idempotency-Key: order-1-capture'               # then CAPTURED
curl -s -X POST localhost:8080/v1/payments/$PAYMENT/refunds -H "Authorization: Bearer $KEY" \
  -H 'Idempotency-Key: order-1-refund-1' -H 'Content-Type: application/json' \
  -d '{"merchantReference":"order-1-refund-1","amountMinor":3000,"reason":"damaged"}'
curl -s localhost:8080/v1/accounts/merchant_payable:m_demo/balance -H "Authorization: Bearer $KEY"
```

Repeating a request with the same `Idempotency-Key` returns the first response; the same key with a different body is a 422. The full API is in [design §7](docs/design.md).

### Triggering faults

mock-psp runs with the `dev` profile in Compose, which enables `/_admin/faults`. A rule applies to the merchant's next operations of one type:

```bash
curl -s -X POST localhost:8081/_admin/faults -H 'Content-Type: application/json' \
  -d '{"merchant_id":"m_demo","type":"CAPTURE","fault":"TIMEOUT_AFTER_COMMIT","times":1}'
curl -s localhost:8081/_admin/faults                  # the active rules
curl -s -X DELETE localhost:8081/_admin/faults        # clear them all
```

| Fault | What to watch |
|---|---|
| `DECLINE` | On `AUTHORIZE`: the payment ends `DECLINED`, with no journal |
| `TIMEOUT_AFTER_COMMIT` | The PSP commits but the response is lost: the worker inquires, and the capture is booked once |
| `DUPLICATE_WEBHOOK` | Twelve deliveries of one outcome, one journal |
| `OUT_OF_ORDER` | A stale `PENDING` after the outcome is ignored |
| `DELAY_WEBHOOK` | The payment stays pending until the late webhook arrives |
| `DROP_WEBHOOK` | The outcome is never sent: the payment stays pending until the safety-net inquiry (15 minutes) or, later, reconciliation |

Operation types are `AUTHORIZE`, `CAPTURE`, `VOID` and `REFUND`. Each fault also has an acceptance test that asserts both databases (AT-06 to AT-09, AT-12).

### Services

| Service | Port | Health |
|---|---|---|
| payment-service | 8080 | http://localhost:8080/actuator/health |
| mock-psp | 8081 | http://localhost:8081/actuator/health |
| notification-service | 8082 | http://localhost:8082/actuator/health; register a merchant endpoint with `PUT /ops/merchants/{id}/webhook-endpoint` and the local ops key `dev-only-ops-key` |
| merchant-webhook-sink | — | Stands in for the merchant: `docker compose -f infra/docker-compose.yml logs merchant-webhook-sink` shows each signed delivery |
| PostgreSQL 16 | 5432 | databases `ledgerpay`, `mockpsp` and `notification`, user and password `ledgerpay` |
| Kafka (KRaft) | 9092 | `payments.events` (outbox relay → notification-service), `payments.events.dlq` |

Stop the stack with `docker compose -f infra/docker-compose.yml down`; add `-v` to delete its data.

## Running the tests

Requires JDK 21 and a running Docker daemon (Testcontainers starts PostgreSQL and Kafka).

```bash
./mvnw test             # unit + Testcontainers integration tests
./mvnw verify           # + acceptance tests against the real mock-psp, format check, coverage gate
./mvnw spotless:apply   # fix formatting
```

[CI](.github/workflows/ci.yml) runs `./mvnw verify` on every push and pull request. JaCoCo fails the build when any module is below 80 % line coverage, counting unit and integration tests; the job summary shows each module's coverage, and the HTML reports are kept as an artifact. Currently 92–96 % per module.

## Architecture decisions

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-money-as-integer-minor-units.md) | Money as integer minor units; HALF_UP fee rounding |
| [0002](docs/adr/0002-idempotency-via-database-unique-constraints.md) | Idempotency via database unique constraints, in three layers |
| [0003](docs/adr/0003-no-postings-on-authorisation-or-void.md) | No ledger postings on authorisation or void |
| [0004](docs/adr/0004-unknown-outcomes-inquire-before-retry.md) | Unknown outcomes: inquire before any retry, with fixed request IDs |
| [0005](docs/adr/0005-refund-reservation-with-pessimistic-locking.md) | Refund reservation with pessimistic locking |
| [0006](docs/adr/0006-transactional-outbox-at-least-once.md) | Transactional outbox; at-least-once delivery with idempotent consumers |
| 0007 | Reconciliation never mutates the ledger; UTC business days (planned with M3) |
| [0008](docs/adr/0008-virtual-threads-for-psp-calls.md) | Virtual threads for PSP calls |

## Performance

Measured on GitHub-hosted runners (4 CPUs, 15 GB RAM, JDK 21) by the [`Benchmarks` workflow](.github/workflows/benchmarks.yml); method in [design §14.1](docs/design.md#141-performance-analysis-jmh-and-jfr). Only measured numbers are shown. A shared runner varies by up to about 25 % between passes, so each comparison is made within one run.

### Under load: JFR found queueing, not CPU, GC or locks

k6 offered up to 50 payments/s, each going create → authorised → capture → captured, with JFR recording payment-service ([baseline run](https://github.com/Kare0638/ledgerpay/actions/runs/36825589849), [after the fix](https://github.com/Kare0638/ledgerpay/actions/runs/36826619023)).

| | Before | After |
|---|---:|---:|
| Payments completed in 5 minutes | 5,528 | **13,465** |
| Payments started per second (offered: 45 on average) | 16.6 | **44.5** |
| Iterations dropped for lack of capacity | 7,619 | **64** |
| Create → authorised, p50 / p99 | 28.0 s / 29.1 s | **1.0 s / 2.0 s** |
| API response, p50 / p99 | 0.9 / 10.2 ms | 1.1 / 12.6 ms |
| GC pause time / longest pause | 0.48 % / 23 ms | 0.57 % / 24 ms |
| Contended monitors (≥ 10 ms) | none of ours | none of ours |
| Virtual-thread pinning | 0 | 0 |

- **What JFR showed before the fix.** The API answered in under a millisecond, GC used under 0.5 % of the time, and no monitor of ours was contended. Yet a payment took 28 s to be authorised. The scheduler thread's time went into `PspOperationWorker.runOnce`: one batch of 20 per 500 ms poll caps the worker at about 40 PSP operations a second, which is about 19 payments a second. Worse, the inbox processor shared Spring's single scheduler thread with the worker.
- **Fix.** The worker now drains (it keeps claiming while batches come back full), and the scheduler has a thread per job.
- **What is next.** At the higher rate, request threads waited for a database connection 67 times, 10 s in total, 525 ms at worst: the next limit to watch.
- Confirmation times are polled once a second, so they are accurate to about a second.

### Virtual threads against platform thread pools (JMH)

The worker's PSP calls, one batch per operation, against a stub PSP in its own JVM ([run](https://github.com/Kare0638/ledgerpay/actions/runs/36823485519), [ADR 0008](docs/adr/0008-virtual-threads-for-psp-calls.md)).

| Batch × PSP delay | virtual | fixed-10 | fixed-50 | fixed-200 |
|---|---:|---:|---:|---:|
| 200 × 200 ms | **346 ms** | 4,157 ms | 906 ms | 337 ms |
| 200 × 20 ms | **142 ms** | 518 ms | 236 ms | 143 ms |
| Peak platform threads | **30** | 35 | 75 | 225 |

- **Virtual threads** match a pool of 200 on latency with 30 platform threads instead of 225, and are 12× faster than a pool of 10 when the PSP is slow.
- **A guess that was wrong.** I expected the connection pool to cap throughput, but pools of 2 to 50 connections made no measurable difference.
- **A JFR finding in the HTTP client.** With virtual threads, peak platform threads were at first higher than with a pool of 10: Spring's `JdkClientHttpRequestFactory` started a new platform thread (`SimpleAsyncTaskExecutor`) for every request, 2,400 of them in 4 s. After giving the `HttpClient` a virtual-thread executor:
  - a batch of 200 calls took **148 ms instead of 224 ms**;
  - peak platform threads fell **from 146 to 30**;
  - a regression test asserts that no platform thread is started per call.
- **The same leak on small machines** ([ADR 0008](docs/adr/0008-virtual-threads-for-psp-calls.md#decision)). The tests began failing intermittently on CI, and limiting them to two CPUs made them fail every time. Thread dumps and JFR found two causes:
  - In the test stub only: its virtual threads pinned both carriers.
  - In production code: the JDK HTTP client hands some stages to `CompletableFuture`'s default executor. With one or two CPUs, that executor starts a platform thread for every task, so every PSP call started one; small containers are exactly that case.
  - The fix is `-Djava.util.concurrent.ForkJoinPool.common.parallelism=2` in the Dockerfile and the test JVMs, with a startup warning if it is missing. All 328 payment-service tests now pass on two CPUs.

## Scope

Single currency (GBP), simulated PSP. Real card-scheme integration, PCI DSS, SCA, FX, chargebacks and merchant payouts are deliberately out of scope; the design document explains why.

## Licence

[MIT](LICENSE)
