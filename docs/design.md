# LedgerPay — Design Document

**Status:** draft, pre-implementation. This document describes the target design; sections are updated as each milestone lands.

---

## 1. Problem statement

A payment platform sits between merchants and an upstream payment service provider (PSP). Almost every interesting failure in such a system happens at the boundaries:

- a client retries because its request timed out, although the first request succeeded;
- the PSP captures the money, but its response never arrives;
- a webhook is lost, duplicated, delayed or delivered out of order;
- two refunds for the same payment arrive at the same moment;
- the PSP's end-of-day settlement report disagrees with our books.

LedgerPay is a small payment, ledger and reconciliation service built to handle exactly these cases. Its goals are:

1. **No duplicate money movement** under retries, concurrency and partial failure.
2. **No silent loss**: every money movement confirmed by the PSP is booked exactly once, and anything that slips through is caught by reconciliation.
3. **A ledger that is always balanced**, enforced by the database and not only by application code.
4. **Traceability**: every state change, journal and reconciliation result can be explained after the fact.

UK payment and e-money institutions are expected by the FCA to reconcile the funds they hold for customers against external records. This project therefore treats reconciliation and "unknown outcome" handling as core features, not add-ons.

---

## 2. Scope

**In scope**
1. Payment lifecycle: authorise → capture → partial or full refunds; void after authorisation. Every money operation is confirmed asynchronously by a simulated PSP.
2. Idempotent write API (`Idempotency-Key` header + database unique constraints).
3. Double-entry ledger with database-enforced invariants; journals are append-only.
4. Reliable PSP calls: persisted operations, leases, exponential backoff, **status inquiry before any retry**.
5. Inbound webhooks: HMAC verification, persisted inbox, de-duplication, quarantine of out-of-order or conflicting events.
6. Partial refunds with transactional reservation of the refundable balance.
7. Transactional outbox + Kafka; a notification service delivers signed webhooks to merchants, with retries and a dead-letter topic.
8. Daily settlement reconciliation against PSP CSV reports.
9. Metrics, dashboards, alert rules, load tests and an AWS deployment.

**Fixed business assumptions**
- Single currency: GBP. Amounts are integer minor units (pence), 1 to 100,000,000 per payment.
- The platform charges merchants a fee (default 1%, configurable in basis points) on capture. Fees are not returned on refund.
- Upstream PSP fees and payouts to merchants are out of scope.

**Deliberately out of scope**: real card-scheme or acquirer integration, PCI DSS, 3-D Secure / SCA, multi-currency and FX, partial capture, authorisation expiry, chargebacks, merchant payouts, a web front end, break-management workflow and manual ledger adjustments. Each of these would change the ledger model and deserves its own design rather than a few extra columns.

---

## 3. Architecture

```
                    ┌──────────────────────────── payment-service ──────────────────────────────┐
 Merchant ──HTTP──▶ │ API ─▶ PaymentService ─▶ Tx①: payment + psp_operation + idempotency       │
 (API key,          │                               + outbox                                    │
  Idempotency-Key)  │                                                                           │
                    │ PspOperationWorker (SKIP LOCKED + lease) ──HTTP──▶ ┌───────────┐          │
                    │   timeout → inquiry → resubmit only if not found    │ mock-psp  │          │
                    │                                                      │ own state │          │
 mock-psp ─webhook─▶│ WebhookController ─verify─▶ inbox_events ─▶ InboxProcessor                  │
  (HMAC-signed)     │                                              └▶ Tx②: final state + journal │
                    │                                                 + outbox + inbox processed │
                    │ SettlementImporter ◀── settlement CSV ── mock-psp /reports/settlement     │
                    │ ReconEngine (one snapshot) → recon_runs, items                            │
                    │   then LedgerIntegrityCheck (own snapshot) → ledger_integrity_checks      │
                    │ OutboxRelay (SKIP LOCKED) ──publish──┐                                    │
                    └──────────────────────────────────────┼────────────────────────────────────┘
                                                           ▼
                                        Kafka topic: payments.events (key = paymentId)
                                                           │
                                                           ▼
                                    notification-service: de-dupe on processed_events
                                    → signed merchant webhooks; 3 retries, then payments.events.dlq
```

**Services**
- `payment-service` — the core. Packages: `payment` (payments, refunds and the reservation, the money transaction), `ledger` (accounts, posting rules, the integrity check), `psp` (client and worker), `webhook` (inbox), `settlement` (report import), `reconciliation` (matching and runs), `outbox`, `idempotency`, `metrics`, `api` and `ops` (merchant and operations endpoints).
- `notification-service` — consumes domain events and delivers merchant webhooks. It never touches money, so an outage delays notifications but cannot corrupt the ledger. That separation is the reason Kafka is used here.
- `mock-psp` — a simulated upstream PSP with **its own schema and state**. Settlement reports are generated from its own records, never copied from our ledger, so reconciliation can find real differences.
- `common` — the `Money` type and fees, HMAC signing, RFC 4180 CSV with formula escaping.

**Principles**
1. **PostgreSQL is the single source of truth for money.** Kafka carries notifications only; it never holds balances or refundable amounts.
2. **No network call inside a transaction that holds money-row locks.** Claim work in a short transaction, call the PSP outside any transaction, then open the money transaction with the result.

**Messaging port:** an `EventPublisher` interface with a `KafkaEventPublisher` and an optional `SqsEventPublisher` (ports and adapters).

---

## 4. Technology

| Layer | Choice |
|---|---|
| Language, framework | Java 21, Spring Boot 3.x (Web, JdbcClient, Validation, Actuator) |
| Database | PostgreSQL 16, Flyway |
| Messaging | Apache Kafka (KRaft, local), Spring Kafka |
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka), Awaitility, a scriptable stub PSP (`StubPsp`); jqwik for property-based tests; `promtool` for alert rules |
| Observability | Micrometer, Prometheus, Grafana, JSON structured logs with trace IDs |
| Load testing | k6 |
| Deployment | Docker Compose; planned: Terraform, AWS (ECR, ECS Fargate, RDS, CloudWatch, SSM Parameter Store), #24 |
| CI | GitHub Actions: build, unit, Testcontainers and acceptance tests, the JaCoCo gate (80 % per service and library module; `benchmarks` is not measured); the two demos on Compose with the alerts check; the alert rules' unit tests; a gitleaks scan of every commit for secrets (`SECURITY.md`) |

JdbcClient is preferred over JPA because `FOR UPDATE`, `SKIP LOCKED`, `RETURNING` and unique-violation handling need precise control over the SQL.

---

## 5. Domain model

### 5.1 Money
- Amounts are `long` minor units (1050 = £10.50) with an ISO 4217 `currency`. Never `double` or `float`, including in JSON (`amountMinor` is an integer).
- Fee = `amount_minor × fee_bps / 10000`, computed in `long` arithmetic and rounded HALF_UP to the penny. Boundary tests cover amounts such as 1p, 149p and 150p.

### 5.2 PSP operations — one mechanism for every money action

Every PSP call (authorise, capture, void, refund) is first persisted as a `psp_operation` with a fixed `psp_request_id`. The request ID never changes across retries, so the PSP can de-duplicate and a second money movement is impossible.

| Status | Meaning |
|---|---|
| `PENDING` | Accepted; outcome unknown (including timeouts and awaiting webhook) |
| `SUCCEEDED` | Confirmed by the PSP (webhook or inquiry) |
| `FAILED` | Explicitly rejected by the PSP |

Final states are immutable. **A timeout is never a failure** — it means "unknown", and the outcome must be inquired.

### 5.3 Payment state machine

```
POST /payments                  capture                         PSP confirms
 ──▶ AUTH_PENDING ──authorised──▶ AUTHORIZED ──────▶ CAPTURE_PENDING ──────▶ CAPTURED ──▶ (partial refunds)
          │                       │    ▲  ▲                 │
       declined              void │    │  └─ capture failed─┘
          ▼                       ▼    │
       DECLINED           VOID_PENDING─┘ void failed
                                  │
                                  │ PSP confirms
                                  ▼
                                VOIDED
```

| From | Trigger | To |
|---|---|---|
| AUTH_PENDING | authorised / declined | AUTHORIZED / DECLINED |
| AUTHORIZED | capture request | CAPTURE_PENDING |
| AUTHORIZED | void request | VOID_PENDING |
| CAPTURE_PENDING | captured / explicit failure | CAPTURED / AUTHORIZED |
| VOID_PENDING | voided / explicit failure | VOIDED / AUTHORIZED |
| CAPTURED | refund request | unchanged; a Refund is created |
| anything else | any request | rejected with 409 |

- An explicit PSP rejection of a capture or a void returns the payment to AUTHORIZED. No money moved and the authorisation still holds, so there is no ledger entry; the merchant may retry the void or capture instead. The rejection reason is kept in `psp_operations.last_error` and raises an alert, because card schemes expect unused authorisations to be released promptly. A timeout is **not** a rejection: the payment stays pending until an inquiry settles the outcome (section 9.1).
- `PaymentStatus.canTransitionTo(target)` is a pure function, unit-tested for every pair.
- `refund_summary` (NONE / PARTIAL / FULL) is derived from amounts and does not replace the payment status.
- Stale events cannot move a final state back to pending; `resource_version` decides ordering.

### 5.4 Refunds and reservation

```
refundable = captured_minor − refunded_minor − refund_reserved_minor
```

- **Accept:** in one transaction, `SELECT … FOR UPDATE` the payment, check the refundable amount, increase `refund_reserved_minor`, create a PENDING refund and its PSP operation. Over-limit requests get 409 `REFUND_AMOUNT_EXCEEDED`.
- **Succeeded:** release the reservation, increase `refunded_minor` and post the refund journal — atomically.
- **Failed:** release the reservation only.
- **Unknown / timeout:** keep the reservation. A timeout must never free refundable balance.
- Database backstop: `CHECK (refunded_minor + refund_reserved_minor <= captured_minor)`.

Pessimistic locking is used because the check is an aggregate read-compute-write; contention per payment is low, and optimistic retries of the whole command are easier to get wrong. See [ADR 0005](adr/0005-refund-reservation-with-pessimistic-locking.md).

Implementation notes:
- A refund's merchant reference is unique per merchant. The same reference for the same payment and amount returns the existing refund, checked before the amount, so a retry is never refused because its own reservation used up the balance. With another payment or amount it is 409 `DUPLICATE_REFERENCE`.
- Only a CAPTURED payment can be refunded (409 `INVALID_STATE`). The payment stays CAPTURED; `refundSummary` reports progress.
- The answer is applied by the money transaction (section 9.3): the reservation is released, into `refunded_minor` and a refund journal on success, and the refund becomes SUCCEEDED or FAILED with a `RefundSucceeded` or `RefundFailed` outbox event.

### 5.5 Double-entry ledger

**Accounts** (all GBP):

| Account | Type | Meaning |
|---|---|---|
| `psp_receivable:{psp}` | Asset | Owed to us by the PSP between capture and settlement |
| `merchant_payable:{merchantId}` | Liability | Owed by us to the merchant |
| `fee_revenue` | Revenue | Platform fees |

**Sign convention:** `postings.amount_minor` is signed — **debit positive, credit negative** — and every journal sums to zero.

| Event | Postings (capture £100, 1% fee, then refund £30) | Notes |
|---|---|---|
| Authorised | **none** | An authorisation holds funds at the issuer; no money has moved |
| Voided | **none** | Same reason |
| Captured | psp_receivable +10000, merchant_payable −9900, fee_revenue −100 | |
| Refunded | merchant_payable +3000, psp_receivable −3000 | Fee is not returned |
| Any failure | none | No money moved |

After a full refund the merchant payable can be negative (for example −100p, the retained fee owed by the merchant). This is expected.

**Invariants** — enforced in the database and verified by tests:
1. Every journal has at least two postings that sum to zero, checked at commit by a `DEFERRABLE INITIALLY DEFERRED` constraint trigger. (A row-level CHECK cannot validate a sum across rows.)
2. Every successful capture or refund has **exactly one** journal: `UNIQUE (psp_operation_id)`. This is the final line of defence against double booking, independent of webhook event IDs.
3. Journals and postings are **append-only**: triggers reject UPDATE, DELETE and TRUNCATE, and postings can only be added in the transaction that created their journal, so a committed journal is closed. Corrections would be new, linked reversal journals.
4. All postings in the system sum to zero; `psp_receivable` = `merchant_payable` + `fee_revenue` (normal-balance view).
5. Balances are derived from postings. Any balance cache must be updated in the same transaction and have a rebuild-and-compare command.
6. A posting's currency matches its account's currency, enforced by a composite foreign key on `(account_id, currency)`.

---

## 6. Database schema (Flyway)

```sql
-- V1__core.sql
CREATE TABLE merchants (
    id            TEXT PRIMARY KEY,
    api_key_hash  TEXT    NOT NULL,
    fee_bps       INT     NOT NULL DEFAULT 100 CHECK (fee_bps BETWEEN 0 AND 10000),
    webhook_url   TEXT
);

CREATE TABLE payments (
    id                     UUID PRIMARY KEY,
    merchant_id            TEXT        NOT NULL REFERENCES merchants(id),
    merchant_reference     TEXT        NOT NULL,
    amount_minor           BIGINT      NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    currency               CHAR(3)     NOT NULL CHECK (currency = 'GBP'),
    status                 TEXT        NOT NULL,
    captured_minor         BIGINT      NOT NULL DEFAULT 0,
    refunded_minor         BIGINT      NOT NULL DEFAULT 0,
    refund_reserved_minor  BIGINT      NOT NULL DEFAULT 0,
    version                BIGINT      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, merchant_reference),
    CHECK (refunded_minor >= 0 AND refund_reserved_minor >= 0),
    CHECK (refunded_minor + refund_reserved_minor <= captured_minor)
);

CREATE TABLE refunds (
    id                  UUID PRIMARY KEY,
    payment_id          UUID        NOT NULL REFERENCES payments(id),
    merchant_id         TEXT        NOT NULL,
    merchant_reference  TEXT        NOT NULL,
    amount_minor        BIGINT      NOT NULL CHECK (amount_minor > 0),
    status              TEXT        NOT NULL,          -- PENDING / SUCCEEDED / FAILED
    reason              TEXT        NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, merchant_reference)
);

CREATE TABLE psp_operations (
    id               UUID PRIMARY KEY,
    payment_id       UUID        NOT NULL REFERENCES payments(id),
    refund_id        UUID        REFERENCES refunds(id),
    type             TEXT        NOT NULL,             -- AUTHORIZE / CAPTURE / VOID / REFUND
    psp_request_id   TEXT        NOT NULL UNIQUE,      -- fixed across retries
    amount_minor     BIGINT      NOT NULL,
    currency         CHAR(3)     NOT NULL,
    status           TEXT        NOT NULL,             -- PENDING / SUCCEEDED / FAILED
    psp_reference    TEXT        UNIQUE,               -- PSP transaction ID
    succeeded_at     TIMESTAMPTZ,                      -- PSP success time, used by reconciliation
    resource_version INT         NOT NULL DEFAULT 0,
    attempts         INT         NOT NULL DEFAULT 0,
    failures         INT         NOT NULL DEFAULT 0,   -- consecutive attempts that established nothing
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until      TIMESTAMPTZ,
    last_error       TEXT,
    needs_review     BOOLEAN     NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_psp_ops_due ON psp_operations(next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE idempotency_keys (
    merchant_id     TEXT        NOT NULL,
    scope           TEXT        NOT NULL,   -- e.g. 'POST /payments'
    idem_key        TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,   -- SHA-256 of the canonical request body
    status          TEXT        NOT NULL,   -- IN_PROGRESS / COMPLETED
    response_code   INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, scope, idem_key)
);

-- V2__ledger.sql
CREATE TABLE accounts (
    id        TEXT PRIMARY KEY,           -- e.g. 'merchant_payable:m_123'
    type      TEXT    NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE')),
    currency  CHAR(3) NOT NULL,
    UNIQUE (id, currency)                 -- target of the postings (account_id, currency) key
);

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    psp_operation_id  UUID        NOT NULL UNIQUE REFERENCES psp_operations(id),
    entry_type        TEXT        NOT NULL CHECK (entry_type IN ('CAPTURE', 'REFUND')),
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      UUID    NOT NULL REFERENCES journal_entries(id),
    account_id    TEXT    NOT NULL,
    amount_minor  BIGINT  NOT NULL CHECK (amount_minor <> 0),
    currency      CHAR(3) NOT NULL,
    FOREIGN KEY (account_id, currency) REFERENCES accounts(id, currency)
);
CREATE INDEX idx_postings_account ON postings(account_id);
CREATE INDEX idx_postings_entry ON postings(entry_id);
-- Plus: a deferred constraint trigger for the journal balance (on journal_entries and postings);
-- triggers rejecting UPDATE, DELETE and TRUNCATE on journals and postings; and a trigger that only
-- accepts postings from the transaction that created their journal. See the migration for details.

-- V3__messaging.sql
CREATE TABLE inbox_events (
    provider      TEXT        NOT NULL,
    event_id      TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    payload_hash  TEXT        NOT NULL,
    status        TEXT        NOT NULL,   -- RECEIVED / PROCESSED / QUARANTINED
    error         TEXT,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,
    PRIMARY KEY (provider, event_id)
);

-- V5__inbox_conflicts.sql: each distinct body that reused a stored event_id, byte for byte
CREATE TABLE inbox_conflicts (
    provider      TEXT        NOT NULL,
    event_id      TEXT        NOT NULL,
    payload_hash  TEXT        NOT NULL,
    payload       TEXT        NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_id, payload_hash),
    FOREIGN KEY (provider, event_id) REFERENCES inbox_events (provider, event_id)
);

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,         -- also the message eventId
    aggregate_id  UUID        NOT NULL,     -- paymentId, used as the Kafka key
    event_type    TEXT        NOT NULL,     -- PaymentCreated / PaymentAuthorized / PaymentCaptured / RefundSucceeded …
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    seq           BIGINT GENERATED ALWAYS AS IDENTITY,  -- V6: relay order (section 10)
    rejections    INT NOT NULL DEFAULT 0,  -- V7: refusals of the event itself (section 10)
    last_error    TEXT,
    quarantined_at TIMESTAMPTZ             -- set after max-rejections; never claimed again
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(seq) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_unpublished_aggregate ON outbox_events(aggregate_id, seq)
    WHERE published_at IS NULL;

-- V8__settlement_reports.sql (#17; V4 to V7 went to #10, #11 and #14). Both tables are
-- append-only, by the same trigger as the journals, and rows are only inserted with a VALID report
-- in the transaction that stores it (ADR 0007).
CREATE TABLE settlement_reports (
    id             UUID PRIMARY KEY,
    psp            TEXT        NOT NULL,
    business_date  DATE        NOT NULL,
    file_sha256    TEXT        NOT NULL,
    version        INT         NOT NULL,
    status         TEXT        NOT NULL,   -- VALID / INVALID
    row_count      INT         NOT NULL,   -- data rows in the file
    error_count    INT         NOT NULL DEFAULT 0,
    errors         JSONB,                  -- [{line, column, message}], the first 1,000
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (psp, business_date, file_sha256),
    UNIQUE (psp, business_date, version)
);

-- Rows of VALID reports only.
CREATE TABLE settlement_rows (
    report_id       UUID    NOT NULL REFERENCES settlement_reports(id),
    line_no         INT     NOT NULL,
    psp_reference   TEXT    NOT NULL,
    psp_request_id  TEXT    NOT NULL,
    txn_type        TEXT    NOT NULL,   -- CAPTURE / REFUND
    merchant_id     TEXT    NOT NULL,
    amount_minor    BIGINT  NOT NULL,
    currency        CHAR(3) NOT NULL,
    succeeded_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (report_id, line_no)
);

-- V9__reconciliation.sql (#18). A run finishes once (RUNNING -> COMPLETED or FAILED) and items
-- are accepted only while it runs; both enforced by triggers (ADR 0007).
CREATE TABLE recon_runs (
    id             UUID PRIMARY KEY,
    report_id      UUID        NOT NULL REFERENCES settlement_reports(id),
    rules_version  TEXT        NOT NULL,
    status         TEXT        NOT NULL,   -- RUNNING / COMPLETED / FAILED
    snapshot_at    TIMESTAMPTZ,            -- when the books were read; set on completion
    summary        JSONB,                  -- counts and amounts per category
    error          TEXT,                   -- why it FAILED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ
);

CREATE TABLE recon_items (
    run_id            UUID  NOT NULL REFERENCES recon_runs(id),
    item_no           INT   NOT NULL,
    category          TEXT  NOT NULL,
    psp_reference     TEXT,
    psp_operation_id  UUID,
    external_line_nos INT[] NOT NULL DEFAULT '{}',
    internal          JSONB,               -- our operation as the snapshot saw it
    diff              JSONB,               -- field-by-field differences
    PRIMARY KEY (run_id, item_no)
);

-- V10__ledger_integrity_checks.sql (#19), append-only
CREATE TABLE ledger_integrity_checks (
    id               UUID PRIMARY KEY,
    trigger          TEXT        NOT NULL,   -- ON_DEMAND / RECON_RUN
    recon_run_id     UUID        UNIQUE REFERENCES recon_runs(id),  -- one check per run
    status           TEXT        NOT NULL,   -- PASSED / FAILED
    snapshot_at      TIMESTAMPTZ NOT NULL,
    journals         BIGINT      NOT NULL,
    postings         BIGINT      NOT NULL,
    violation_count  INT         NOT NULL,
    violations       JSONB,                  -- [{check, subject, detail}], the first 1,000
    checked_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- V12__assistant.sql (#26). Analyses are append-only; a proposal is decided once.
CREATE TABLE assistant_analyses (
    id          UUID PRIMARY KEY,
    run_id      UUID        NOT NULL REFERENCES recon_runs(id),
    item_no     INT         NOT NULL,           -- with run_id, references recon_items
    model       TEXT        NOT NULL,
    status      TEXT        NOT NULL,           -- COMPLETED / INVALID_OUTPUT / FAILED
    analysis    JSONB,                          -- the validated output; null unless COMPLETED
    error       TEXT,                           -- why it is not COMPLETED
    tool_calls  JSONB       NOT NULL,           -- [{tool, args, ok}] in order, for audit
    turns       INT         NOT NULL,
    tokens      INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- V13__assistant_precedents.sql (#64), set on insert like every other column:
--   break_signature  TEXT          what kind of break, without IDs, times or amounts
--   break_embedding  vector(768)   its embedding (pgvector); null if the call failed
--   precedent_ids    UUID[]        the earlier analyses it was given, in order

CREATE TABLE inquiry_proposals (
    id                UUID PRIMARY KEY,
    analysis_id       UUID NOT NULL UNIQUE REFERENCES assistant_analyses(id),
    psp_operation_id  UUID NOT NULL REFERENCES psp_operations(id),
    reason            TEXT NOT NULL,
    status            TEXT NOT NULL,            -- PROPOSED / CONFIRMED / REJECTED
    decided_by        TEXT,
    decided_at        TIMESTAMPTZ,
    outcome           TEXT,                     -- SCHEDULED / ALREADY_FINAL / OPERATION_NOT_FOUND
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

notification-service database (`notification`):

```sql
CREATE TABLE merchant_endpoints (
    merchant_id     TEXT PRIMARY KEY,
    webhook_url     TEXT        NOT NULL,
    signing_secret  TEXT        NOT NULL,   -- per merchant; shown once at registration
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_deliveries (
    event_id         UUID PRIMARY KEY REFERENCES processed_events(event_id),
    merchant_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    payment_id       UUID        NOT NULL,
    status           TEXT        NOT NULL,   -- DELIVERED / NO_ENDPOINT
    webhook_url      TEXT,
    response_status  INT,
    delivered_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`merchants.webhook_url` in payment-service's database is not used: notification-service owns where and how webhooks are sent (section 10).

---

## 7. API

Merchants authenticate with `Authorization: Bearer <api-key>`. The key is stored as its SHA-256 in `merchants.api_key_hash`, which has a unique index. A plain hash is enough because keys are long and random, not passwords. **The merchant is always taken from the authenticated principal**; a merchant ID in the body can never override it. Resources belonging to another merchant return 404, so existence is not leaked.

Operations endpoints (`/ops/**`) use a separate ops key, also `Authorization: Bearer`. Only its SHA-256 is configured (`ledgerpay.ops.api-key-hash`, from `OPS_API_KEY_HASH`) and compared in constant time. With none configured the endpoints are off (401), and a merchant key never opens them.

A merchant can read one account's balance: its own `merchant_payable:{merchantId}`. Any other account ID, another merchant's payable or a platform account, is 404. The balance is derived from postings with the ledger's sign convention (section 5.5), so it is negative while the platform owes the merchant money. Before the first posting it is 0.

`POST /ops/psp-operations/{id}/inquiry` makes a pending operation due now and clears `needs_review` and its consecutive failures, so the worker inquires on its next poll (section 9.1). While a worker holds the operation's lease it is 409 `INVALID_STATE`: the worker would overwrite the reset with the failure count it claimed, so the operator tries again once the call ends. A final operation is 409 too. It is how an operator retries an operation set aside for review.

Every response carries a trace ID in the `X-Trace-Id` header, in the body of 202 responses and in every Problem Details body. It always identifies the current request: stored idempotent responses have no trace ID, and a replay gets its own. There is no distributed tracing: a servlet filter generates it per request and puts it in the logging MDC. The same filter also puts there the request's `Idempotency-Key`, cut to its first 255 characters, and the payment ID when the path names one (section 13).

Every merchant write requires an `Idempotency-Key` header. A settlement report upload needs none: the file's SHA-256 makes it idempotent.

| Method | Path | Purpose | Success | Typical errors |
|---|---|---|---|---|
| POST | `/v1/payments` | Create a payment and start authorisation | 202 | 400 / 409 / 422 |
| POST | `/v1/payments/{id}/capture` | Capture | 202 | 404 / 409 |
| POST | `/v1/payments/{id}/void` | Void | 202 | 404 / 409 |
| POST | `/v1/payments/{id}/refunds` | Partial or full refund | 202 | 404 / 409 `REFUND_AMOUNT_EXCEEDED` |
| GET | `/v1/payments/{id}` | Status, captured, refunded, reserved, refundable | 200 | 404 |
| GET | `/v1/refunds/{id}` | Refund details | 200 | 404 |
| GET | `/v1/accounts/{id}/balance` | Balance derived from postings | 200 | 404 |
| POST | `/webhooks/psp` | Inbound PSP webhook (HMAC, not API key) | 200 | 401 / 400 / 503 |
| POST | `/ops/settlement-reports?businessDate=&psp=` | Upload a PSP settlement CSV (`text/csv`) | 201, or 200 for an identical file | 400 `INVALID_CSV` / 413 / 415 |
| GET | `/ops/settlement-reports/{id}`, `/ops/settlement-reports?businessDate=&psp=` | One version with its errors; every version of a day | 200 | 404 |
| POST | `/ops/recon-runs` | Reconcile a specific report version: `{"reportId": …}` | 202 (RUNNING) | 404 / 409 `INVALID_STATE` for an INVALID report |
| GET | `/ops/recon-runs/{id}`, `/ops/recon-runs?reportId=` | A run with its summary; every run of a report, oldest first | 200 | 404 |
| GET | `/ops/recon-runs/{id}/items?category=&offset=&limit=` | Item-level breaks with their report lines, our operation and the diff; up to 1,000 a page | 200 | 400 / 404 |
| POST | `/ops/psp-operations/{id}/inquiry` | Trigger a PSP status inquiry | 202 | 404 |
| POST | `/ops/recon-runs/{id}/items/{itemNo}/analysis` | Ask the assistant to analyse one break (section 16) | 201, COMPLETED / INVALID_OUTPUT / FAILED | 404 / 409 for a matched item / 503 `ASSISTANT_UNAVAILABLE` |
| GET | `/ops/assistant/analyses/{id}` | An analysis with its tool calls and proposal | 200 | 404 |
| POST | `/ops/assistant/proposals/{id}/confirm`, `…/reject` | A person decides a proposed inquiry: `{"decidedBy": …}` | 200 | 400 / 404 / 409 once decided, or while the worker holds the operation |
| POST | `/ops/ledger-integrity-checks` | Check the ledger against itself now (section 11.2) | 201, PASSED or FAILED | |
| GET | `/ops/ledger-integrity-checks/{id}`, `/ops/ledger-integrity-checks?limit=` | One check with its violations; the latest checks | 200 | 400 / 404 |
| GET | `/actuator/health`, `/actuator/prometheus` | Health (open) and metrics (ops key) | 200 | 401 for metrics without the ops key |

A settlement upload names the business day; `psp` defaults to the configured provider (`mock-psp`). The response is the stored version: `reportId`, `psp`, `businessDate`, `version`, `status`, `rowCount`, `errorCount`, `errors`, `fileSha256`, `uploadedAt`. An INVALID file is still kept as a version (section 11.1), so the 400 `INVALID_CSV` problem carries its `reportId`, `version`, `rowCount`, `errorCount` and `errors`, each `{line, column, message}`. Uploading the same INVALID file again returns the same 400.

A refund is requested with `{ "merchantReference": "refund-1", "amountMinor": 3000, "reason": "returned" }`, in the payment's currency. The 202 response carries `refundId`, `paymentId`, `status` (PENDING) and `statusUrl` (`/v1/refunds/{id}`).

Writes return **202 Accepted**, not 200: the money outcome is only known once the PSP confirms. Clients poll the resource or wait for a merchant webhook.

```http
POST /v1/payments
Authorization: Bearer mk_test_...
Idempotency-Key: 7f1c2a9e-...
Content-Type: application/json

{ "merchantReference": "order-1001", "amountMinor": 10000, "currency": "GBP" }
```

```http
HTTP/1.1 202 Accepted
{ "paymentId": "…", "status": "AUTH_PENDING", "statusUrl": "/v1/payments/…", "traceId": "…" }
```

Errors use RFC 7807 Problem Details with extra `code` and `traceId` fields, and optional `fieldErrors` and `retryAfterSeconds`. **Every** error has a `code`, including Spring MVC's own (malformed JSON, wrong method or media type) and unexpected ones. A **temporary** database failure is 503 `TEMPORARILY_UNAVAILABLE` with a `Retry-After` header: the database is unreachable, a statement times out or deadlocks, a transaction cannot begin, or a commit or rollback fails because the connection was lost mid-transaction (outcome unknown, so the client retries with the same key). The same classification applies in the authentication filter and in the controllers. Any other database error is a bug and returns 500 `INTERNAL_ERROR`. The `Bearer` scheme name is case-insensitive.

| HTTP | code | What the client should do |
|---|---|---|
| 400 | VALIDATION_ERROR / INVALID_CSV | Fix the request; nothing was accepted and the key is not consumed |
| 401 / 403 | UNAUTHENTICATED / FORBIDDEN | Check credentials; do not retry |
| 404 | RESOURCE_NOT_FOUND | Does not exist or belongs to another merchant |
| 405 / 406 / 413 / 415 | METHOD_NOT_ALLOWED / NOT_ACCEPTABLE / PAYLOAD_TOO_LARGE / UNSUPPORTED_MEDIA_TYPE | Fix the request; nothing was accepted |
| 409 | IDEMPOTENCY_IN_PROGRESS | A request with the same key is still running; retry later with the same key |
| 409 | INVALID_STATE / REFUND_AMOUNT_EXCEEDED / DUPLICATE_REFERENCE | Read the current state before deciding |
| 422 | IDEMPOTENCY_KEY_REUSED | The key was used for a different request; do not switch keys blindly |
| 500 | INTERNAL_ERROR | Unexpected; report the `traceId`. Retrying with the same key is safe |
| 503 | TEMPORARILY_UNAVAILABLE | Retry with the **same** key after `Retry-After`; do not assume the first call was not accepted |

---

## 8. Idempotency

Key scope = merchant + operation (`scope`) + key.

1. Insert `idempotency_keys(..., status = 'IN_PROGRESS')` with `ON CONFLICT DO NOTHING`, in the same transaction as step 2.
   - **Inserted:** new request, continue.
   - **Conflict:** a concurrent request with the same key waits here until the first one commits or rolls back. Then read the existing row —
     - different `request_hash` → **422**;
     - `COMPLETED` → replay the stored status and body with `Idempotent-Replayed: true`;
     - `IN_PROGRESS` → **409**, retry later.
2. In the **same database transaction**: create or update the business object, write the `psp_operation`, write the outbox event, mark the key `COMPLETED` with the 202 response. Because the key and the work commit together, other requests never see `IN_PROGRESS` in practice; concurrent duplicates wait briefly and then get the replay.
3. If validation fails and the transaction rolls back, the key rolls back too and can be reused.
4. The transaction makes **no PSP call**; the worker does that later (section 9), which keeps the transaction short.

**Three layers of de-duplication**

| Layer | Constraint | Protects against |
|---|---|---|
| Request | `PRIMARY KEY (merchant_id, scope, idem_key)` | Client retries of the same HTTP request |
| Business | `UNIQUE (merchant_id, merchant_reference)` | The same order submitted with a new key |
| Ledger | `UNIQUE (journal_entries.psp_operation_id)` | Duplicate webhooks, webhook racing an inquiry |

"Check, then insert" is not enough: under concurrency two requests can both see "not found". Only a database unique constraint guarantees a single winner. Idempotency keys are kept for 24 hours; business references and journal keys are kept permanently.

The request hash is the SHA-256 of the **parsed** request re-serialised with sorted properties, so whitespace, field order and ignored fields do not turn a retry into a 422. A reused `merchantReference` with the same amount returns the existing payment; with a different amount it is 409 `DUPLICATE_REFERENCE`, which rolls back the whole transaction, key included. See [ADR 0002](adr/0002-idempotency-via-database-unique-constraints.md).

---

## 9. Reliable PSP calls and recovery

### 9.1 PspOperationWorker

```sql
-- short transaction: claim due operations and take a lease
UPDATE psp_operations SET lease_until = now() + interval '30 seconds', attempts = attempts + 1
WHERE id IN (
    SELECT id FROM psp_operations
    WHERE status = 'PENDING' AND next_attempt_at <= now()
      AND (lease_until IS NULL OR lease_until < now())
    ORDER BY next_attempt_at
    LIMIT 20
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

The PSP is then called **outside** any transaction:

| Situation | Action |
|---|---|
| First attempt | Submit with `psp_request_id` |
| Retry after timeout or error | **Inquire by `psp_request_id` first**; act on the result if found; resubmit with the **same** request ID only if the PSP has no record |
| PSP says "accepted" | Store `psp_reference`; set `next_attempt_at` to +15 min as a safety-net inquiry in case the webhook never arrives |
| PSP returns a final state | Run the money transaction (9.3) |
| Timeout, 5xx, connection error | Stay PENDING, record `last_error`, back off 1, 2, 4, 8 … s up to 60 s with jitter |
| 10 consecutive failed attempts | Set `needs_review`, alert; **business state unchanged**, reservation not released |

Backoff follows the consecutive failures (`failures`, reset whenever the PSP is reached, so safety-net inquiries never add up): 1, 2, 4, 8 … s capped at 60 s with equal jitter (half fixed, half random). A failed call releases the lease and sets `next_attempt_at`; operations with `needs_review` are never claimed. See [ADR 0004](adr/0004-unknown-outcomes-inquire-before-retry.md).

If a worker dies mid-flight, the lease expires and another worker picks the operation up. The fixed request ID and the journal unique key make the repeat harmless.

### 9.2 Inbound webhooks (inbox)

1. Read the raw body; verify `X-PSP-Timestamp` (5-minute tolerance) and `X-PSP-Signature` = `HMAC-SHA256(secret, timestamp + "." + rawBody)` with a constant-time comparison (`MessageDigest.isEqual`). Failure → 401.
2. Schema validation failure → 400.
3. Insert into `inbox_events`; return 200 **only after the insert commits**. If the database is unavailable return 503 so the PSP redelivers. Never acknowledge first and persist later.
4. `(provider, event_id)` conflict: same payload → 200; different payload → 200, but record the conflict (`inbox_conflicts`) and alert without overwriting the original.
5. `InboxProcessor` validates the event against the `psp_operation` (request ID, merchant, type, amount, currency). Unknown operation or mismatched fields → `QUARANTINED`. Quarantined events never touch the ledger and never create payments.

Webhook fields: `event_id`, `event_type`, `psp_request_id`, `psp_reference`, `merchant_id`, `amount_minor`, `currency`, `status`, `occurred_at`, `resource_version`.

Implementation notes:
- The operation type comes from `event_type` (`capture.succeeded` → CAPTURE). `status` is SUCCEEDED, FAILED or PENDING; anything else is 400. A PENDING event is stored and processed like any other but never applied (section 9.3), so an event overtaken in delivery is acknowledged and leaves a record instead of being redelivered until the PSP gives up.
- `payload_hash` is the SHA-256 of the raw body, so "same payload" means byte for byte. A conflicting body under a known `event_id` is logged at ERROR, kept once per distinct body in `inbox_conflicts`, and acknowledged; the stored original is never changed.
- `InboxProcessor` claims one RECEIVED event at a time with `FOR UPDATE SKIP LOCKED` and marks it PROCESSED or QUARANTINED in the same transaction as the money transaction, so it is applied exactly once.
- A temporary database failure leaves the event RECEIVED for the next poll. Any other exception would fail the same way forever, so the event is quarantined with the error instead of being retried.

### 9.3 The money transaction (shared by webhooks and inquiries)

```
BEGIN
  SELECT … FROM payments WHERE id = ? FOR UPDATE        -- fixed lock order: payment → refund
  SELECT … FROM psp_operations WHERE id = ? FOR UPDATE
  event does not match the operation → QUARANTINE (unknown request ID, merchant, type, amount, reference)
  event reports PENDING             → never applied: "stale" if the operation is final, else nothing to do
  already in the same final state  → mark inbox PROCESSED, return
  already in a different final state → QUARANTINE + alert, at any resource_version; never roll back posted business
  older resource_version            → ignore
  update operation status and succeeded_at; update payment/refund status and captured/refunded/reserved
  capture or refund succeeded       → insert journal and postings (psp_operation_id unique)
  write outbox event; mark inbox PROCESSED
COMMIT      -- the deferred balance constraint is checked here
```

The two partial failures:
- **PSP succeeded, local commit failed:** nothing was written; the webhook redelivery or safety-net inquiry re-runs the transaction.
- **Local commit succeeded, the 200 never reached the PSP:** the PSP redelivers; the "same final state" branch returns without side effects.

Before anything changes, the event must match the operation: merchant (from the payment), operation type, amount and currency, and the `psp_reference` once one is known. A mismatch, an unknown `psp_request_id` or a contradiction of a final state is quarantined. Inquiries use the same transaction (`MoneyTransaction`) with the operation's own fields, so a webhook and an inquiry for one outcome serialise on the payment and operation locks; whichever comes second finds it already applied. Should the locks ever fail, `UNIQUE (psp_operation_id)` still allows only one journal.

### 9.4 mock-psp

A separate Spring Boot service with its own schema. Endpoints: submit an operation (idempotent on `psp_request_id`; same ID with different parameters is rejected), inquire by request ID, export a daily settlement CSV (`GET /reports/settlement?date=YYYY-MM-DD`, section 11.1).

The wire format is snake_case JSON; errors are Problem Details with a `code`.

| Method | Path | Result |
|---|---|---|
| POST | `/v1/operations` | **201** new operation; **200** repeat with the same parameters, returning its current state; **409** `REQUEST_ID_CONFLICT` same ID with different parameters; **422** `INVALID_REFERENCE` unknown parent, wrong parent type or another merchant's; **400** `INVALID_REQUEST` |
| GET | `/v1/operations/{psp_request_id}` | **200** current state; **404** `NOT_FOUND` — the only answer that allows a resubmit (section 9.1) |

Submit body: `psp_request_id`, `merchant_id`, `type`, `parent_reference`, `amount_minor`, `currency`. Capture and void name the authorisation's `psp_reference` as parent, a refund names the capture's, and an authorisation has none. The response is the operation: the same fields plus `psp_reference`, `status`, `failure_reason`, `resource_version`, `created_at`, `succeeded_at`.

- **Accepted** operations are `PENDING` and succeed after `mockpsp.settle-delay`; the outcome and its webhook are written in one transaction.
- **Declined** operations are recorded as `FAILED` at once, with a `failure_reason`, and reported by webhook like any outcome: parent not succeeded, amount or currency differs from the authorisation (no partial capture), authorisation already captured or voided, or refunds exceeding the capture. Nothing is recorded for a 4xx.
- Decisions about one parent take its row lock, so concurrent captures or refunds cannot both pass the checks; a partial unique index also allows at most one pending or successful capture or void per authorisation.
- A trigger rejects any update to an operation already in a final state.
- **Webhooks** are stored with their exact body and sent by a poller outside any transaction, signed at send time (section 9.2). Anything other than a 2xx is retried after 1, 2, 4 … s, up to 60 s, for up to 20 attempts; a redelivery has the same `event_id` and body and a fresh timestamp and signature. `event_type` is `<type>.<status>`, e.g. `capture.succeeded`.

Fault injection (dev profile only):

| Fault | Verifies |
|---|---|
| `DECLINE` | Explicit failure; reservation released |
| `TIMEOUT_AFTER_COMMIT` | PSP succeeded but the response timed out → inquire, do not resubmit |
| `DROP_WEBHOOK` | Lost webhook → safety-net inquiry or reconciliation catches it |
| `DUPLICATE_WEBHOOK` | Same event ID ×10; different event IDs for the same outcome |
| `OUT_OF_ORDER` | A stale event cannot move state backwards |
| `DELAY_WEBHOOK` | Late delivery still converges |

Faults are selected through `POST /_admin/faults` with `{merchant_id, type?, fault, times?}`, which applies the fault to the next `times` new operations of that merchant and type, or to every one. `GET` lists the rules, `DELETE` clears them, and `DELETE /{id}` removes one. The controller exists only under the `dev` profile (the local Compose stack); elsewhere these paths are 404. Rules are held in memory, and the fault is stored with each operation so that settling and delivery can act on it. Repeats are never faulted, and responses never reveal a fault.

| Fault | What mock-psp does |
|---|---|
| `DECLINE` | Records the operation as FAILED with `DECLINED`, unless a real decline reason applies, and sends it by webhook |
| `TIMEOUT_AFTER_COMMIT` | Commits the operation, which succeeds as usual, then holds the submit response for `mockpsp.faults.response-delay` (10 s), beyond the caller's read timeout |
| `DROP_WEBHOOK` | Settles without queueing any webhook |
| `DUPLICATE_WEBHOOK` | Sends the outcome 10 times under one event ID, and once each under two more event IDs |
| `OUT_OF_ORDER` | After the outcome, sends the operation's PENDING event with the previous `resource_version`, due `stale-event-delay` (1 s) later and never before the outcome is delivered; none for an operation that failed when submitted, which was never PENDING |
| `DELAY_WEBHOOK` | Sends the outcome after `webhook-delay` (30 s) |

`MockPspFaults` (mock-psp tests) configures faults from any test over plain HTTP.

---

## 10. Transactional outbox and Kafka

Writing to the database and publishing to Kafka cannot share a transaction. Without an outbox, either the database commits and the event is lost, or the event is published and the database rolls back. The outbox stores the event in the same transaction as the business change; a relay publishes it afterwards.

```sql
SELECT * FROM outbox_events e
WHERE published_at IS NULL AND quarantined_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM outbox_events earlier          -- only each payment's
                  WHERE earlier.aggregate_id = e.aggregate_id  -- earliest unpublished event
                    AND earlier.published_at IS NULL
                    AND earlier.seq < e.seq)
ORDER BY seq
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

- Publish to `payments.events` keyed by `aggregate_id` (payment ID) to preserve per-payment ordering.
- Order by `seq`, taken at insert under the payment's lock, not `created_at` (the transaction's start time). A payment's next event waits until the previous one is published, so concurrent relays cannot reorder one payment's events; `SKIP LOCKED` lets them work on different payments.
- Claim, publish, wait for the acknowledgements and set `published_at` in one transaction; unacknowledged events stay for the next run. This is **at-least-once**: a crash between publish and update re-sends the event, so consumers must be idempotent.
- A failure is the broker's or the event's (`EventPublisher.rejectsEvent`; for Kafka, a record too large, not serialisable or invalid). The broker's stops the batch and counts against nothing. The event's lets the batch go on and is counted; after `max-rejections` (3) the event is quarantined: not claimed again, an alert, and its payment's later events wait behind it while other payments carry on.
- Producer: `acks=all`, `enable.idempotence=true`, and timeouts of seconds, because the relay holds its rows while it waits.
- Message: key = payment ID; value = `{eventId, eventType, paymentId, occurredAt, data}`, where `eventId` is the outbox row's `id`; `eventId` and `eventType` are also headers.
- See [ADR 0006](adr/0006-transactional-outbox-at-least-once.md).

**notification-service**
- Owns merchant webhook endpoints: `PUT /ops/merchants/{merchantId}/webhook-endpoint` (ops key) sets the URL and generates a **per-merchant** signing secret, returned once; registering again rotates it. A shared secret would let one merchant forge another's webhooks, and a secret cannot travel in an event, so the endpoint lives with the service that signs. The secret is stored in the clear because signing needs it; production would keep it in a secrets manager.
- For each event: skip it if its `eventId` is in `processed_events`; with no endpoint for the merchant, record `NO_ENDPOINT`; otherwise POST the envelope, then record `processed_events` and the delivery in one transaction.
- Sends HMAC-signed webhooks — the same scheme as inbound PSP webhooks, in the opposite direction: `X-LedgerPay-Signature` = `HMAC-SHA256(secret, timestamp + "." + body)`, with `X-LedgerPay-Timestamp`, `X-LedgerPay-Event-Id` and `X-LedgerPay-Event-Type`. A crash between the POST and the record sends the webhook again, so merchants de-duplicate on the event ID.
- Three retries with exponential backoff (1, 2, 4 s), then `payments.events.dlq` (Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`), with the original key and value and the exception in headers. A malformed event is dead-lettered at once. The retries block the partition; a production system would use a delivery table with scheduled retries, because merchant endpoints can be down for hours.
- notification-service's own database being unavailable (connection refused, timeouts, no transaction) is not the event's fault and is never dead-lettered: those failures are retried every `max-interval` (10 s) until the database is back, holding the partition, which could not progress without it anyway. Otherwise a short outage would dead-letter every event consumed during it, and nothing replays the dead-letter topic.
- `PUT /ops/merchants/{id}/webhook-endpoint` accepts only an `http(s)` URL that parses as a URI with a host; anything else is 400.
- Offsets are committed per record (`ack-mode: record`) once it is handled: delivered, skipped or dead-lettered.

End-to-end exactly-once is not realistic for business side effects; the design is **at-least-once delivery + idempotent consumers**, i.e. effectively-once. And **ledger correctness does not depend on Kafka**: if Kafka is down, balances stay correct and only notifications are delayed.

---

## 11. Settlement reconciliation

### 11.1 Settlement reports
- mock-psp exports one CSV per business day containing **successful captures and refunds only** (authorisations and voids move no money).
- Business day is UTC: `[D 00:00:00Z, D+1 00:00:00Z)`. Real UK operations often cut over on Europe/London time, where clock-change days are 23 or 25 hours long; UTC avoids that and the choice is recorded in an ADR.
- Columns: `psp_reference, psp_request_id, txn_type, merchant_id, amount_minor, currency, succeeded_at`. mock-psp writes them in that order with CRLF line ends (RFC 4180); the import accepts them in any order. `succeeded_at` is ISO-8601 UTC ending in `Z`; amounts are positive minor units.
- The whole file is validated on import (`SettlementCsvParser`). Strict UTF-8; exactly the seven columns; per row, `txn_type` CAPTURE or REFUND, a positive whole-number amount, an ISO 4217 currency, a `Z` timestamp inside the business day, and references and merchant without surrounding spaces or control characters. Blank lines and wrong field counts are errors, not skipped rows. Any error, or more than 100,000 rows, makes the report `INVALID` with line-level errors and no rows, and **no reconciliation result is produced** from it. Rows are never silently dropped.
- More than 10 MB is refused with 413 before anything is stored; Tomcat drains up to 64 MB of the refused body so the client still reads the 413.
- An identical file (SHA-256) returns the existing report. Any other file becomes the day's next version, INVALID ones included, under an advisory lock on the PSP and day. Old versions are kept unchanged; each run is bound to one version (ADR 0007).
- CSV written for people escapes cells beginning with `= + - @`, a tab or a carriage return with a leading `'`, against spreadsheet formula injection. The import undoes exactly that escape (`Csv.unescapeFormula`), for the PSPs that apply it (`formula-escaping-psps`, `mock-psp` by default); from any other PSP a leading `'` is data.

### 11.2 Matching and classification

A run is stored RUNNING and carried out in the background (`ReconEngine`); the client polls it. The internal side is read in a single `REPEATABLE READ` read-only transaction (`BooksSnapshot`), so the whole run sees one consistent snapshot whatever commits meanwhile; `snapshot_at` is recorded. The internal set is every successful capture and refund whose `succeeded_at` falls in the business day, plus any local operation the report refers to by reference or request ID, whatever its type or status (to detect operations still PENDING locally). Classification (`Reconciler`) is a pure function of the report's rows and that set.

Matching is one-to-one on `psp_reference`, falling back to `psp_request_id`. Every reference is matched before any request ID, so a row with a wrong reference cannot take an operation from a later row that names it exactly; a duplicate group falls back to the request ID too when all its rows carry the same one. **Matching on amount and approximate time alone is forbidden.**

| Category | Rule | Handling |
|---|---|---|
| `MATCHED` | One-to-one; type, amount, currency, merchant and date agree, and the operation is SUCCEEDED | Recorded, no alert |
| `DUPLICATE_EXTERNAL` | Same PSP reference on several rows of one report | Whole group is a break; no row is picked to match |
| `MISSING_INTERNAL` | In the report, not found locally | Keep the external record and investigate with the PSP; **never create business from the report** |
| `MISSING_EXTERNAL` | Succeeded locally that day, absent from the report | Check for dropped rows, report version and success date |
| `AMOUNT_MISMATCH` | Matched, but type, amount, currency, merchant or date differ (or the reference, when matched by request ID) | Field-level diff `{field: {external, internal}}`; **never overwrite local amounts** |
| `STATUS_MISMATCH` | Succeeded at the PSP, PENDING or FAILED locally | The operator triggers an inquiry for the item's `pspOperationId`; PENDING resolves through normal posting; conflicting final states need review |

The primary category follows the order duplicate → unmatched → field conflict → status conflict, so every external row ends up in exactly one category, and so does every internal record the report must have (succeeded that day) or that a row was matched to; the counts are disjoint. Other operations in the snapshot are only candidates for matching. A row whose request ID leads to an operation another row already has is a DUPLICATE_EXTERNAL item of its own. Only the day of `succeeded_at` is compared, not the time. A run never sends inquiries itself (ADR 0007). The snapshot is not filtered by PSP: one PSP is configured and `psp_operations` does not record which PSP an operation went to. Supporting several will need that column, and a snapshot of the report's PSP only.

A separate **ledger integrity check** (`LedgerIntegrityCheck`, #19) runs regardless of the report, with every reconciliation run and on demand. It reads the whole ledger in one `REPEATABLE READ` read-only transaction and finds:

| Violation | What it means |
|---|---|
| `MISSING_JOURNAL` | A succeeded capture or refund has no journal |
| `UNEXPECTED_JOURNAL` | A journal books an operation that did not succeed, or is of another type |
| `UNBALANCED_JOURNAL` | A journal has fewer than two postings, or its postings do not sum to zero |
| `LEDGER_NOT_ZERO` | All postings in a currency do not sum to zero |
| `JOURNAL_OPERATION_MISMATCH` | A journal, balanced or not, does not book exactly its operation's postings (section 5.5): the configured PSP's receivable by the amount, the merchant's own payable by its share and `fee_revenue` by the fee (recomputed from `fee_bps`, which never changes for a merchant), nothing to any other account, all in the operation's currency |
| `PAYMENT_TOTALS_MISMATCH` | A payment's `captured_minor` or `refunded_minor` disagrees with its journals |

There is no separate balance cache: balances are always summed from postings. The payment's captured and refunded amounts are the ledger's cache, kept in the money transaction, and the last check compares them with the postings. "At most one journal per operation" is a unique index, which nothing can bypass, so it is not re-checked. The database's triggers (V2) prevent the rest; the check finds what gets past them, such as a migration, a manual fix or a restore run with the triggers off.

Each check is kept (`ledger_integrity_checks`, append-only, with the first 1,000 violations and the count), and a run shows the one that ran with it as `ledgerIntegrity`. A failed check does not fail the run, since the report still reconciles as it does, but it is a **blocking alert**: an error log, and `ledger_invariant_violations`, the latest stored check's count, read on every scrape so that every instance reports the same check, which an alert rule fires on above zero. A day whose report matches to the penny can still fail it (AT-19).

### 11.3 Results
- Completed runs are **immutable**, items included; each item keeps our operation as the snapshot saw it. After a fix, a new run is created and old runs remain for comparison. A run renews `heartbeat_at` every fifth of `ledgerpay.recon.abandoned-after` (10 minutes) while it executes; one whose heartbeat is older than that was left by a dead process and is marked FAILED, and the report can simply be run again. A long run that is still at work is never swept. A run the executor refuses (shutdown) is FAILED at once.
- The summary shows raw external row count, unique transaction count, captured, refunded and net for the report and for our books, and per category the items, report rows and our records in it and their amount. **Equal net totals never replace item-level matching.**
- Reconciliation only reports; it **never mutates the ledger**. Fixes go through the normal path (inquiry → money transaction).

---

## 12. Testing

| Type | What | Tools |
|---|---|---|
| Unit | Every state transition; fee rounding boundaries; every journal balances; reconciliation rules | JUnit 5, AssertJ |
| Integration | Full flows against real PostgreSQL; deferred constraint, unique constraints and append-only triggers actually fire | Testcontainers |
| Property-based | ≥ 1,000 random payment / capture / void / partial-refund histories, refunds one at a time or two at once, each outcome delivered in the submit response, by webhook, duplicated, followed by a stale event, after a timeout and an inquiry, or before the call; after each: the ledger integrity check, refunded + reserved ≤ captured with nothing left reserved, and every answer, status, journal count and the merchant's balance against a model of the history (`PaymentHistoryPropertyTest`, #20). jqwik prints the failing seed and the shrunk history; CI runs 200 | jqwik |
| Messaging | Rolled-back transaction leaves no outbox row; relay restart re-publishes; duplicate delivery processed once; poison message reaches DLQ | Testcontainers Kafka, Awaitility |
| Fault injection | Acceptance tests below | mock-psp, `StubPsp` |
| CI | Full suite on every push; build fails below 80% line coverage | GitHub Actions, JaCoCo |

### Acceptance tests

The cross-service acceptance tests live in the `acceptance-tests` module. It runs payment-service in the test JVM and mock-psp from its runnable jar as a separate process, under the `dev` profile so that faults can be injected; both use one PostgreSQL container with a database each. The two services cannot share a classpath, because their `application.yml` and `db/migration` would collide. The module needs mock-psp's jar, so it runs in `./mvnw verify`, not in `test` alone. Each test sets up its own data and is repeatable. Tests assert **both** the local database and mock-psp state; an HTTP success is never taken as proof of the money effect.

| ID | Input or fault | Expected |
|---|---|---|
| AT-01 | Authorise and capture £100 | One journal, three balanced postings; receivable 10000, payable −9900, revenue −100 |
| AT-02 | Same idempotency key, 50 concurrent creates | One payment, one PSP authorisation; others get the same response or 409 |
| AT-03 | Same key, amount changed 10000 → 12000 | 422; original payment and PSP state unchanged |
| AT-04 | New key, same merchantReference | Same parameters → existing payment; different → 409 |
| AT-05 | Two concurrent captures | One accepted; booked once |
| AT-06 | `TIMEOUT_AFTER_COMMIT` on capture | Inquiry books it once; mock-psp shows a single capture |
| AT-07 | Same event ID ×10; different event IDs for one outcome | Both de-duplication layers hold; one journal |
| AT-08 | Bad signature, stale timestamp, wrong amount, unknown request ID | 401 or quarantined; no ledger change; traceable record |
| AT-09 | Stale PENDING after success; then a conflicting FAILED | No regression; conflict quarantined; no reversing entry |
| AT-10 | £100 refunded as £30 then £70 | Two refund journals; `refund_summary` = FULL; receivable = 0 |
| AT-11 | £70 refundable; concurrent £50 and £40 | At most one accepted; refunded + reserved ≤ captured at all times |
| AT-12 | Refund times out, then fails / succeeds | Timeout keeps the reservation; failure releases only; success releases and books once |
| AT-13 | Kill the worker before / after the money commit | Before: full rollback, retry succeeds. After: redelivery adds no journal |
| AT-14 | Cross-merchant reads of payments, refunds, balances | 404; no data leak |
| AT-15 | `DROP_WEBHOOK`, reconcile, inquire, reconcile again | First run STATUS_MISMATCH; posted once; new run all MATCHED; old run kept |
| AT-16 | CSV with bad amount, missing column, wrong date | Whole report INVALID with line errors; no run result |
| AT-17 | Duplicate row, external-only, internal-only, amount diff | Correct categories; every row traceable; equal nets do not hide breaks |
| AT-18 | New webhook posts during a running reconciliation | Current run unaffected; a new run reflects it |
| AT-19 | Delete a journal or corrupt a balance cache in a test DB | Integrity check fails and alerts, even when PSP amounts all match |

### Demo data set

One merchant, one business day:

| Payment | Amount | Scenario |
|---|---|---|
| P1 | £100 | Captured, then refund R1 £30 |
| P2 | £200 | Captured at the PSP, **webhook lost** |
| P3 | £50 | Captured on both sides |
| P4 | £40 | Authorisation declined |

The settlement report has four rows: P1, R1, P2, P3. The first run shows STATUS_MISMATCH for P2. After an inquiry and a re-run, everything matches: captures 35000, refunds 3000, net 32000.

The ledger has four journals. Normal-balance view: receivable **32000** = merchant payable **31650** + fee revenue **350**. P4 has no journal and is not in the report.

Four single-fault report variants — duplicated P1 row, missing P3 row, P3 amount 5100, an unknown transaction — each ship with their own expected-result file. [`demo/run.sh`](../demo/README.md) plays the day through the API and mock-psp's faults (`DROP_WEBHOOK` for P2, `DECLINE` for P4) on a stack of its own. It adds two scenes that leave the numbers unchanged: two concurrent refunds of P1, one refused with 409 and the other declined by the PSP, and P3's outcome delivered twelve times. Each run, the variants included, is compared with its file in `demo/expected/`.

---

## 13. Observability

**Metrics** (Micrometer, served at `/actuator/prometheus` behind the ops key; every series carries `application`). Event metrics are counted where things happen (`PaymentMetrics`); state is read from the database in one query, cached 5 s and NaN while the database is unreachable (`OperationalGauges`).

| Metric | Type | What |
|---|---|---|
| `payments_total{operation, outcome}` | counter | PSP outcomes applied by the money transaction, counted after commit; a duplicate that changes nothing is not counted |
| `http_server_requests_seconds{method, uri, status}` | histogram | API latency and errors (Spring's, with buckets for p99); stands in for the planned `payment_request_duration_seconds` |
| `idempotency_replays_total` | counter | Requests answered from their stored response |
| `psp_operations_pending`, `psp_operation_oldest_pending_seconds`, `psp_operations_needs_review` | gauges | PSP operations without an outcome, the oldest one's age (leaving out those set aside for review, which have their own alert), those set aside for review |
| `psp_call_duration_seconds{operation, outcome}`, `psp_call_errors_total{operation}` | histogram, counter | PSP calls by how they ended: accepted, final, unknown; unknown is an error |
| `inbox_quarantined_total`, `inbox_quarantined_events`, `webhook_signature_failures_total` | counter, gauge, counter | Webhooks quarantined (ever, and still waiting) and refused for their signature |
| `outbox_unpublished_events`, `outbox_publish_lag_seconds`, `outbox_quarantined_events` | gauges | The outbox backlog, its oldest event's age, events the broker refused |
| `refund_reserved_minor` | gauge | Refund amounts reserved and awaiting the PSP |
| `recon_breaks{category}`, `recon_run_duration_seconds{status}`, `recon_runs_failed_total` | gauge, histogram, counter | Items per category summed over the latest completed run of each PSP and business day, so a clean run of one day cannot hide another's open breaks; runs this process carried out, by how they ended; runs that became FAILED, wherever (a failed run, a refused start, the sweeper), registered at 0 so the first one after a start is an increase |
| `ledger_invariant_violations`, `ledger_integrity_checks_total{status}` | gauge, counter | The latest integrity check's violations (must always be 0) |
| `webhook_deliveries_total{result}`, `notification_dead_lettered_total` | counters | notification-service: merchant webhooks delivered, without endpoint, failed, duplicate; events dead-lettered |
| `kafka_consumer_fetch_manager_records_lag_max` | gauge | notification-service's consumer lag (Kafka client metrics) |

**Dashboard** (`infra/grafana/dashboards/ledgerpay.json`, provisioned): money-safety tiles (ledger violations, operations needing review, quarantined webhooks, oldest pending operation, outbox backlog, reconciliation breaks), outcome throughput, API p99 and error rate, PSP call p99 and errors, outbox, merchant webhooks and dead letters, consumer lag, the latest run's categories (per PSP and day) and reserved refunds.

**Logs:** JSON in Compose (Spring Boot's logstash format), with the MDC as fields: `traceId`, `idempotencyKey` and `paymentId` on API requests, `paymentId` and `pspRequestId` while a PSP call or an outcome is handled, `eventId` and `paymentId` in notification-service. API keys, signing secrets and auth headers are never logged; a test scans the output of requests that carry them.

**Alerts** (Prometheus rules in `infra/prometheus/alerts.yml`, each with a `promtool` unit test in `alerts.test.yml`):

| Alert | Fires when | Severity |
|---|---|---|
| `LedgerInvariantViolated` | `ledger_invariant_violations > 0` | blocking |
| `PspOperationPendingTooLong` | oldest pending operation not set aside for review over 5 minutes, for 1 minute | page |
| `PspOperationNeedsReview` | any operation set aside for review | page |
| `WebhookQuarantined` | any quarantined webhook | page |
| `OutboxBacklog` | oldest unpublished event over a minute old, for 5 minutes | page |
| `OutboxEventQuarantined` | any outbox event refused by the broker | page |
| `EventDeadLettered` | a dead-lettered event in the last 10 minutes | page |
| `ReconciliationRunFailed` | an increase of `recon_runs_failed_total` in the last hour | ticket |
| `ReconciliationBreaks` | any item not MATCHED in the latest completed run | ticket |
| `ServiceDown` | a scrape target down for 1 minute | page |

`demo/alerts.sh` shows two of them firing on the demo stack: the break the reconciliation demo ends with, and a signed webhook for an operation we never asked for. No Alertmanager is run; firing alerts are listed at Prometheus's `/alerts`.

---

## 14. Load testing (k6)

1. **Steady traffic:** create → capture with mock-psp in instant-success mode; ramp up; record throughput and p99.
2. **Retry storm:** the same idempotency key 10,000 times in a short window; exactly one capture.
3. **Mixed:** 80% reads, 20% writes across create, capture, refund and void.
4. **Reconciliation throughput:** 100,000-row report from validation to completed run.

Webhook-to-commit latency is measured separately from API acceptance latency. Results are published with machine specification, data volume and the commit hash of the scripts. Only measured numbers are reported.

### 14.1 Performance analysis (JMH and JFR)

**Virtual threads vs a platform thread pool (JMH).** The `PspOperationWorker` claims up to 20 operations and calls the PSP outside any transaction (section 9.1), so the executor that runs those calls decides how much PSP latency the worker can overlap. A JMH benchmark in a separate `benchmarks` module compares:

- `Executors.newVirtualThreadPerTaskExecutor()`;
- `Executors.newFixedThreadPool(n)` for n = 10, 50, 200.

Each operation is one `runOnce()` over a freshly seeded batch, through payment-service's own worker, PSP client and SQL, recording each acceptance through a bounded HikariCP pool. The PSP is a stub in **its own JVM**, so that its threads never appear in the benchmark JVM's counts, answering PENDING after a fixed delay (0, 20 and 200 ms) with `TCP_NODELAY` on; without it, Nagle and delayed ACKs added about 40 ms per response and swamped the delay. Parameters: executor, PSP delay, batch size (20, 200), connection-pool size, and the PSP client's HTTP executor (below).

Measured:
- latency per batch (`Mode.SampleTime`: mean, p50, p99), from a pass without JFR so recording overhead never touches it;
- threads and heap, from a JFR pass with the same parameters:
  - `jdk.JavaThreadStatistics` for peak *platform* threads;
  - `jdk.GCHeapSummary` for heap used after each GC;
  - `jdk.VirtualThreadStart` and `jdk.VirtualThreadEnd`, off by default and enabled for this pass, giving peak live virtual threads, which platform-thread statistics and `ThreadMXBean` do not include;
  - `jdk.VirtualThreadPinned`, because on JDK 21 a virtual thread that blocks inside `synchronized` holds its carrier thread.
- The JMH GC profiler (`-prof gc`) reports allocation rate and GC count and time only, so it is not used for any of these.

**First finding (JFR).** With the virtual-thread executor, peak platform threads were *higher* than with a pool of 10: 2,400 platform threads named `SimpleAsyncTaskExecutor-N` were started in a 4-second recording, one per PSP call. Spring's `JdkClientHttpRequestFactory` writes request bodies on the `HttpClient`'s executor and, when the client has none, falls back to a `SimpleAsyncTaskExecutor` that starts a new platform thread per request, whatever thread made the call. `PspClient` now gives its `HttpClient` a virtual-thread executor. The `httpExecutor` parameter (`virtual`, `spring-default`) keeps the before and after measurable in the same run, and `PspOperationWorkerIntegrationTest` asserts that a batch of calls starts no platform thread per call.

`benchmarks/run.sh` runs every pass and `benchmarks/analyze.py` writes the report. The published numbers come from the `Benchmarks` workflow, run by hand or by labelling a pull request `run-benchmarks`, on a GitHub-hosted runner whose CPU, memory, JDK and commit are written into the report. A shared runner is noisier than a dedicated host, so every comparison is made within one run.

The expected result, to be confirmed or refuted by the numbers: virtual threads win when PSP latency dominates, and the gain stops at the connection-pool size, because the pool — not the thread count — bounds concurrent money transactions. The chosen executor and its limits are recorded in ADR 0008.

**Profiling under load (JFR).** `load-tests/run-jfr.sh` brings up the Compose stack with `infra/docker-compose.perf.yml`, which starts payment-service with

```bash
-XX:StartFlightRecording=settings=profile,filename=/tmp/payment-service.jfr,dumponexit=true -Xmx1g
```

and makes mock-psp settle at once. It then runs the k6 steady scenario (`load-tests/steady.js`: an arrival rate ramping to `RATE` payments per second, each going create → authorised → capture → captured) and stops payment-service so that the recording is written. `load-tests/analyze_jfr.py` reads the recording through `JfrExtract.java` (`jdk.jfr.consumer`). `jfr print --json` with deep stacks would be gigabytes, and without `--stack-depth` it shows only five frames, which hides the waiting site. Parks of idle pool, scheduler and selector threads are left out, so that what remains are waits for a resource, each with its waiting site and thread.

| Question | JFR events |
|---|---|
| How long and how often does GC pause? | `jdk.GarbageCollection`, `jdk.GCPhasePause`, `jdk.GCHeapSummary` |
| Which locks are contended, and by whom? | `jdk.JavaMonitorEnter`, `jdk.ThreadPark` (with stack traces) |
| Do virtual threads pin? | `jdk.VirtualThreadPinned` |
| Where does CPU and allocation go? | `jdk.ExecutionSample`, `jdk.ObjectAllocationSample` |

**Second finding (load).** At 50 payments/s offered, the first recording showed 16.6 payments/s completed and 28 s from create to authorised, while the API answered in 0.9 ms, GC took 0.48 % of the time and no monitor of ours was contended. The scheduler thread's time went into `PspOperationWorker.runOnce`: one batch of 20 per 500 ms poll caps the worker at about 40 PSP operations a second, and the inbox processor shared Spring's single scheduler thread with it. The worker now drains while batches come back full, and each scheduled job has a thread. The recording after the fix kept up with the offered 44.5 payments/s, with authorisation in 1.0 s p50 and 2.0 s p99 (README, Performance).

Findings go into the README's Performance section: GC pause p50 / p99 / max and total pause time, the top contended monitors with their call sites, any change made because of them, and the before/after numbers. Nothing is measured on WSL, whose clock jumps distort timing; the JDK version, JVM flags, CPU, memory and commit hash are published with every number.

---

## 15. Deployment

- **Local:** Docker Compose — PostgreSQL, Kafka, Prometheus, Grafana and the three services.
- **AWS (Terraform):** default VPC; ECR; ECS Fargate for the three services; RDS PostgreSQL (db.t4g.micro); CloudWatch Logs; SSM Parameter Store for database credentials and signing secrets.
- **Messaging on AWS:** either a single Kafka task on ECS for demos, or the `SqsEventPublisher` adapter with SNS + SQS. MSK is avoided for cost.
- The AWS stack is applied for demos and destroyed afterwards; a budget alert guards spend.

---

## 16. Optional: reconciliation assistant

An operator asks for an analysis of one break (`POST /ops/recon-runs/{id}/items/{itemNo}/analysis`). A model, Gemini through its REST API, gets the break's facts and returns a likely cause, its evidence and next steps. It is off unless a key is configured (`GEMINI_API_KEY`, or `GEMINI_API_KEY_FILE` for a Compose secret); everything else runs without it. [ADR 0009](adr/0009-the-assistant-proposes-a-person-decides.md) records the guardrails.

**Facts, not access.** The question comes with what the read-only tools return for the break: the item, its report lines and diff (`get_break`), and its operation's timeline (`get_operation_timeline`: attempts, last error, journals, webhooks received). The model may call `get_operation_timeline` or `find_operations` (by PSP reference) for anything more. The tools read through the same queries as the reconciliation API; none writes, and none reaches merchants, keys or anything outside the break. Preloading the facts keeps an analysis to one or two model calls, which matters on Gemini's free tier (20 requests a day per model).

**One action, proposed.** `propose_inquiry` is the model's only action, and it only records a proposal: at most one per analysis, and only for this break's own operation while it is PENDING. Anything else is refused and the model told why. A person confirms it (`…/proposals/{id}/confirm`), which goes through the same path as `POST /ops/psp-operations/{id}/inquiry` (section 7), or rejects it. Proposing changes nothing; the ledger is reached only as it always is, through an inquiry's result (section 9.3).

**A fixed output, validated.** The model finishes by calling `submit_analysis`, whose parameters are the schema: `likelyCause` (one of `OUTCOME_NOT_RECEIVED`, `CONFLICTING_OUTCOME`, `DUPLICATE_IN_REPORT`, `MISSING_FROM_REPORT`, `FIELD_MISMATCH`, `UNKNOWN_TRANSACTION`, `INSUFFICIENT_EVIDENCE`), `confidence`, `explanation`, 1–8 `evidence` lines, up to 5 `nextSteps`. It is checked before it is stored: the enums, the lengths, and every UUID or PSP reference cited must be one the tools returned. A refused submission goes back once with the reason; a second is stored as `INVALID_OUTPUT`, with nothing to show. Eight model calls without a valid analysis is `FAILED`.

**Kept.** Every analysis is stored, append-only, with the model, its tool calls in order, turns and tokens (`assistant_analyses`, section 6); a proposal is decided once (`inquiry_proposals`).

**Precedents** (#64). An analysis also gets up to three earlier ones as hints: for each other break its latest COMPLETED analysis, unless the inquiry it proposed is still open or was rejected. A break is its operation, else its PSP reference, else its item: a break that stays open is an item of every run until it closes, and a report run again repeats them all, so its analyses in other runs are its own, never precedents of it. The break is described without IDs, times, amounts or free text (category, the fields that differ, how our operation, its payment, journals and webhooks stand; whether there was a last error, not its text; a status that is not a plain enum word reads `other`), that text is embedded with Gemini's embedding model, and the closest are found by cosine distance in Postgres (pgvector; same category first, then distance, then the newest). An exact scan is enough at this size, so there is no vector index. A precedent is shown as that description, its `likelyCause` and `confidence`, and whether a person confirmed its inquiry: not its explanation, evidence or next steps, which are the model's free text about another break, with amounts and dates the validator would not stop being repeated as facts. Precedents are not facts: an ID only they hold is refused like an invented one. Without an embedding the analysis goes ahead with none; each analysis records the precedents it was given. `ledgerpay.assistant.max-precedents=0` turns them off, for comparing the evaluation with and without; breaks are still embedded, so analyses made meanwhile are precedents once they are on again. V13 creates the `vector` extension, which needs a superuser: where Flyway's role is not one, an administrator creates it first.

**Calls to Gemini.** The key goes in the `x-goog-api-key` header only, and is redacted from every error (SECURITY.md). 429, 500 and 503 are retried with backoff, but a 429 for a daily quota fails at once, and a wait Google asks for is honoured only up to 60 seconds. A model that cannot be reached is 503 `ASSISTANT_UNAVAILABLE`. Tests run against a stub; only synthetic demo data goes to the free tier, which Google may use to improve its products.

**Evaluation.** The four report variants and the lost webhook of the demo (section 12) are the evaluation set, each with its expected cause and proposal; the results, failures included, are published in the README.

---

## 17. Repository layout

```
ledgerpay/
├── README.md
├── SECURITY.md               # where secrets live, the scanning layers, what to do if one leaks
├── .gitleaks.toml            # secret scanning rules and the allowlist of public dev values
├── docs/
│   ├── design.md             # this document
│   ├── adr/                  # 0001–0008
│   └── images/               # the dashboard screenshot
├── common/                   # Money and fees, HMAC signing, CSV
├── payment-service/
│   └── src/main/java/.../
│       ├── api/              # merchant API, ProblemDetail, auth filters, trace IDs
│       ├── payment/          # payments, refunds and the reservation, state machine, money transaction
│       ├── ledger/           # accounts, posting rules; integrity/ the ledger integrity check
│       ├── psp/              # PSP client, PspOperationWorker, inquiry-first retries
│       ├── webhook/          # verification, inbox, InboxProcessor
│       ├── settlement/       # settlement report import and validation
│       ├── reconciliation/   # snapshot, matching, classification, runs
│       ├── outbox/           # OutboxRelay, EventPublisher and the Kafka adapter
│       ├── idempotency/
│       ├── metrics/          # event metrics and database gauges
│       └── ops/              # operations endpoints
├── notification-service/     # merchant webhooks from Kafka, DLQ
├── mock-psp/                 # simulated PSP, fault injection, settlement export
├── acceptance-tests/         # AT-xx across services: payment-service in-process, mock-psp as a process
├── load-tests/               # k6 scenario and the JFR recording under load
├── benchmarks/               # JMH benchmarks
├── demo/                     # the reconciliation demo, expected results, the alerts check
├── scripts/                  # demo.sh (the happy path in one command), lib.sh
│   └── git-hooks/            # pre-commit: gitleaks on the staged changes
├── infra/
│   ├── docker-compose.yml    # the stack, Prometheus and Grafana
│   ├── docker-compose.perf.yml # override for the JFR recording under load (section 14.1)
│   ├── postgres/             # init.sql: one database per service
│   ├── prometheus/           # scrape config, alerts.yml and its unit tests
│   ├── grafana/              # provisioning and the LedgerPay dashboard
│   └── terraform/            # planned (#24)
└── .github/workflows/        # ci.yml, benchmarks.yml
```

### Architecture decisions

| ADR | Decision |
|---|---|
| [0001](adr/0001-money-as-integer-minor-units.md) | Money as integer minor units; HALF_UP fee rounding |
| [0002](adr/0002-idempotency-via-database-unique-constraints.md) | Idempotency via database unique constraints, in three layers |
| [0003](adr/0003-no-postings-on-authorisation-or-void.md) | No ledger postings on authorisation or void |
| [0004](adr/0004-unknown-outcomes-inquire-before-retry.md) | Unknown outcomes: inquire before any retry, with fixed request IDs |
| [0005](adr/0005-refund-reservation-with-pessimistic-locking.md) | Refund reservation with pessimistic locking |
| [0006](adr/0006-transactional-outbox-at-least-once.md) | Transactional outbox; at-least-once delivery with idempotent consumers |
| [0007](adr/0007-reconciliation-never-mutates-the-ledger-utc-business-days.md) | Reconciliation never mutates the ledger; UTC business days; reports kept as versions |
| [0008](adr/0008-virtual-threads-for-psp-calls.md) | Virtual threads for PSP calls |
| [0009](adr/0009-the-assistant-proposes-a-person-decides.md) | The reconciliation assistant reads facts and proposes; a person decides |

All are accepted. Each ADR records context and constraints, the decision, rejected alternatives, trade-offs and the tests that cover it.

---

## 18. Roadmap

Work is tracked as [GitHub milestones](https://github.com/Kare0638/ledgerpay/milestones), one issue per deliverable, each linked to its acceptance tests.

| Milestone | Contents | Exit criteria | Status |
|---|---|---|---|
| **M1 — Ledger and core flow** | Multi-module Maven build, Compose, Flyway V1–V2; Money, fees, state machine; ledger with deferred balance constraint and append-only triggers; idempotency; mock-psp happy path; PspOperationWorker; webhook inbox; money transaction | AT-01 – AT-05 | Done |
| **M2 — Failure handling and events** | Fault injection; inquiry-first retries and backoff; partial refunds with reservation; outbox, relay, Kafka; notification-service with DLQ; CI with coverage gate; ADR 0001–0006 | AT-06 – AT-14; `docker compose up` runs the full flow | Done: `scripts/demo.sh` |
| **M3 — Reconciliation and observability** | Settlement export and import; classification; ledger integrity check; property-based ledger tests; demo data and variants; metrics, dashboard, alert rules, JSON logs; ADR 0007 | AT-15 – AT-19; recorded end-to-end demo | All but the recording: AT-15 – AT-19 pass, `demo/run.sh` (#21) |
| **M4 — Performance and cloud** | k6 scenarios; Terraform deployment to AWS; optional SQS adapter and reconciliation assistant | Published load-test results; deployment evidence | Open: #23, #24; optional #25, #26 |
| **M5 — Performance analysis** | JMH benchmark of PSP dispatch on virtual threads vs a platform thread pool; JFR recording under k6 load, analysed for GC pauses and lock contention; ADR 0008 | Benchmark results and JFR findings in the README (section 14.1) | Done |

Idempotency, inquiry-first retries, refund reservation, database-enforced ledger invariants and reconciliation are the core of the project and are not traded away for schedule.
