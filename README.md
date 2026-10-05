# LedgerPay

A payment, double-entry ledger and settlement reconciliation service in Java 21 and Spring Boot, built around the failure modes that matter in payments: client retries, PSP timeouts with unknown outcomes, lost or duplicated webhooks, concurrent refunds, and books that disagree with the PSP's settlement report.

> **Status (October 2026):** the core payment flow, the ledger and failure handling around the PSP are built and tested; refunds, the Kafka pipeline and reconciliation are next. Progress is tracked on the [milestones](https://github.com/Kare0638/ledgerpay/milestones).

## Built and tested

- **Idempotent payments API**: `Idempotency-Key` plus database unique constraints at three layers: request, merchant reference and journal ([ADR 0002](docs/adr/0002-idempotency-via-database-unique-constraints.md)). Fifty concurrent creates with one key make one payment (AT-02).
- **Authorise, capture and void** against a simulated PSP. Every money operation is persisted first, called outside any transaction by a worker using `SKIP LOCKED` and leases, and confirmed asynchronously.
- **Unknown outcomes handled safely**: a timeout is never treated as a failure. Every retry inquires by a fixed request ID before it may resubmit, with exponential backoff. After 10 consecutive failures the operation is set aside for review, and the payment stays pending ([ADR 0004](docs/adr/0004-unknown-outcomes-inquire-before-retry.md)). A capture whose response is lost is found by inquiry and booked once (AT-06); a worker killed before or after the money commit never books twice (AT-13).
- **Double-entry ledger with database-enforced invariants**: a deferred balance constraint, append-only journals and exactly one journal per settled operation ([ADR 0001](docs/adr/0001-money-as-integer-minor-units.md), [ADR 0003](docs/adr/0003-no-postings-on-authorisation-or-void.md)). Property-based tests with jqwik cover fees and posting rules.
- **Signed webhooks through a persisted inbox**: HMAC with a timestamp window. Events are de-duplicated by event ID and by outcome. Stale events are ignored. Unknown, mismatched or contradicting events are quarantined with the reason, and conflicting bodies are kept for investigation (AT-07, AT-08, AT-09).
- **A mock PSP with fault injection**: decline, timeout after commit, dropped, duplicated, out-of-order and delayed webhooks, used by acceptance tests that run payment-service against the real mock-psp process and PostgreSQL.
- **Performance analysis**: a JMH benchmark of PSP calls on virtual threads against platform thread pools, and JFR recordings under k6 load ([below](#performance)).

Acceptance tests from the [design's list](docs/design.md): AT-01 to AT-09 and AT-13 pass, 10 of 19.

## Planned

| Milestone | What | Issues |
|---|---|---|
| M2: failure handling and events | Partial refunds with a transactional reservation, so concurrent refunds never exceed the capture | #12 |
| | Merchant isolation: no cross-merchant reads (API-key authentication is in place) | #13 |
| | Transactional outbox relay to Kafka. Outbox rows are already written in the money transaction; nothing publishes them yet | #14 |
| | notification-service: idempotent consumer, signed merchant webhooks, dead-letter topic | #15 |
| | CI quality gate and a README you can run end to end | #16 |
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

planned: payment-service ──outbox──▶ Kafka ──▶ notification-service ──▶ signed merchant webhooks
```

See [docs/design.md](docs/design.md) for the full design: state machine, posting rules, schema, API, recovery paths, reconciliation rules and the acceptance test list.

## Tech stack

Java 21 · Spring Boot 3 · PostgreSQL 16 · Flyway · Testcontainers · jqwik · JMH · JFR · k6 · Docker Compose

Planned: Apache Kafka · Micrometer / Prometheus / Grafana · Terraform · AWS (ECS Fargate, RDS)

## Running locally

Requires JDK 21 and Docker.

```bash
./mvnw test                                    # unit + Testcontainers integration tests
./mvnw verify                                  # + acceptance tests against the real mock-psp, + format check
./mvnw spotless:apply                          # fix formatting
docker compose -f infra/docker-compose.yml up --build -d
```

| Service | Port | Health |
|---|---|---|
| payment-service | 8080 | http://localhost:8080/actuator/health |
| mock-psp | 8081 | http://localhost:8081/actuator/health |
| notification-service | 8082 | http://localhost:8082/actuator/health (a skeleton until #15) |
| PostgreSQL 16 | 5432 | databases `ledgerpay` and `mockpsp` |
| Kafka (KRaft) | 9092 | started, not yet used (#14) |

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
