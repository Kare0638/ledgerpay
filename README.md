# LedgerPay

A payment, double-entry ledger and settlement reconciliation service in Java 21 and Spring Boot, built around the failure modes that matter in payments: client retries, PSP timeouts with unknown outcomes, lost or duplicated webhooks, concurrent refunds, and books that disagree with the PSP's settlement report.

> **Status:** Implementation is in progress; track it on the [milestones](https://github.com/Kare0638/ledgerpay/milestones) and [issues](https://github.com/Kare0638/ledgerpay/issues).

## What it demonstrates

- **Idempotent payments API** — `Idempotency-Key` plus database unique constraints at three layers: request, merchant reference and journal.
- **Authorise / capture / void / partial refund** lifecycle against a simulated PSP, with every money operation confirmed asynchronously.
- **Unknown outcomes handled safely** — a timeout is never treated as a failure; the service inquires by a fixed request ID before any retry, so money never moves twice.
- **Double-entry ledger with database-enforced invariants** — a deferred balance constraint, append-only journals and exactly one journal per settled operation.
- **Refund reservation** — refundable balance is reserved transactionally, so concurrent refunds can never exceed the captured amount.
- **Signed webhooks through a persisted inbox**, with de-duplication and quarantine of out-of-order or conflicting events.
- **Transactional outbox + Kafka** — at-least-once delivery with idempotent consumers and a dead-letter topic; ledger correctness does not depend on the broker.
- **Settlement reconciliation** — PSP CSV reports matched item by item on a consistent snapshot, with classified breaks; reconciliation never mutates the ledger.
- **Performance analysis** — JMH benchmark of PSP calls on virtual threads vs a platform thread pool, and a JFR recording under load analysed for GC pauses and lock contention.
- **Observability and evidence** — Micrometer, Prometheus, Grafana, alert rules, k6 load tests, fault-injection acceptance tests on real PostgreSQL and Kafka via Testcontainers.

## Architecture

```
Merchant ──▶ payment-service ──▶ PostgreSQL (source of truth for money)
                 │    ▲
      PSP calls  │    │  signed webhooks, settlement CSV
                 ▼    │
               mock-psp (own state, fault injection)

payment-service ──outbox──▶ Kafka ──▶ notification-service ──▶ signed merchant webhooks
```

See [docs/design.md](docs/design.md) for the full design: state machine, posting rules, schema, API, recovery paths, reconciliation rules and the acceptance test list.

## Tech stack

Java 21 · Spring Boot 3 · PostgreSQL 16 · Flyway · Apache Kafka · Testcontainers · jqwik · Micrometer / Prometheus / Grafana · k6 · Docker Compose · Terraform · AWS (ECS Fargate, RDS)

## Running locally

Requires JDK 21 and Docker.

```bash
./mvnw verify                                  # unit + Testcontainers integration tests + format check
./mvnw spotless:apply                          # fix formatting
docker compose -f infra/docker-compose.yml up --build -d
```

| Service | Port | Health |
|---|---|---|
| payment-service | 8080 | http://localhost:8080/actuator/health |
| mock-psp | 8081 | http://localhost:8081/actuator/health |
| notification-service | 8082 | http://localhost:8082/actuator/health |
| PostgreSQL 16 | 5432 | databases `ledgerpay` and `mockpsp` |
| Kafka (KRaft) | 9092 | |

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

## Scope

Single currency (GBP), simulated PSP. Real card-scheme integration, PCI DSS, SCA, FX, chargebacks and merchant payouts are deliberately out of scope; the design document explains why.

## Licence

[MIT](LICENSE)
