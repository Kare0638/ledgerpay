# LedgerPay

A payment, double-entry ledger and settlement reconciliation service in Java 21 and Spring Boot, built around the failure modes that matter in payments: client retries, PSP timeouts with unknown outcomes, lost or duplicated webhooks, concurrent refunds, and books that disagree with the PSP's settlement report.

> **Status:** design stage. Implementation is in progress; track it on the [milestones](https://github.com/Kare0638/ledgerpay/milestones) and [issues](https://github.com/Kare0638/ledgerpay/issues).

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

## Scope

Single currency (GBP), simulated PSP. Real card-scheme integration, PCI DSS, SCA, FX, chargebacks and merchant payouts are deliberately out of scope; the design document explains why.

## Licence

[MIT](LICENSE)
