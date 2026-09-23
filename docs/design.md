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
                    │ ReconciliationService ◀── settlement CSV ── mock-psp /reports/settlement │
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
- `payment-service` — the core. Internal modules: payment, refund, ledger, psp (client and worker), webhook (inbox), reconciliation, outbox, idempotency.
- `notification-service` — consumes domain events and delivers merchant webhooks. It never touches money, so an outage delays notifications but cannot corrupt the ledger. That separation is the reason Kafka is used here.
- `mock-psp` — a simulated upstream PSP with **its own schema and state**. Settlement reports are generated from its own records, never copied from our ledger, so reconciliation can find real differences.
- `common` — event contracts, the `Money` type, HMAC signing utilities.

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
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL, Kafka), Awaitility, WireMock; jqwik for property-based tests |
| Observability | Micrometer, Prometheus, Grafana, JSON structured logs with trace IDs |
| Load testing | k6 |
| Deployment | Docker Compose, Terraform, AWS (ECR, ECS Fargate, RDS, CloudWatch, SSM Parameter Store) |
| CI | GitHub Actions: build → unit tests → Testcontainers integration tests → JaCoCo coverage gate |

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
          │                        │    ▲                   │
       declined                   void   └── capture failed ─┘
          ▼                        ▼
       DECLINED              VOID_PENDING ──PSP confirms──▶ VOIDED
```

| From | Trigger | To |
|---|---|---|
| AUTH_PENDING | authorised / declined | AUTHORIZED / DECLINED |
| AUTHORIZED | capture request | CAPTURE_PENDING |
| AUTHORIZED | void request | VOID_PENDING |
| CAPTURE_PENDING | captured / explicit failure | CAPTURED / AUTHORIZED |
| VOID_PENDING | voided | VOIDED |
| CAPTURED | refund request | unchanged; a Refund is created |
| anything else | any request | rejected with 409 |

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

Pessimistic locking is used because the check is an aggregate read-compute-write; contention per payment is low, and optimistic retries of the whole command are easier to get wrong.

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
3. Journals and postings are **append-only**; triggers reject UPDATE and DELETE. Corrections would be new, linked reversal journals.
4. All postings in the system sum to zero; `psp_receivable` = `merchant_payable` + `fee_revenue` (normal-balance view).
5. Balances are derived from postings. Any balance cache must be updated in the same transaction and have a rebuild-and-compare command.

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
    type      TEXT    NOT NULL,           -- ASSET / LIABILITY / REVENUE
    currency  CHAR(3) NOT NULL
);

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    psp_operation_id  UUID        NOT NULL UNIQUE REFERENCES psp_operations(id),
    entry_type        TEXT        NOT NULL,   -- CAPTURE / REFUND
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id            BIGSERIAL PRIMARY KEY,
    entry_id      UUID    NOT NULL REFERENCES journal_entries(id),
    account_id    TEXT    NOT NULL REFERENCES accounts(id),
    amount_minor  BIGINT  NOT NULL CHECK (amount_minor <> 0),
    currency      CHAR(3) NOT NULL
);
CREATE INDEX idx_postings_account ON postings(account_id);
-- Plus: deferred constraint trigger for journal balance; triggers rejecting UPDATE/DELETE on journals and postings.

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

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,         -- also the message eventId
    aggregate_id  UUID        NOT NULL,     -- paymentId, used as the Kafka key
    event_type    TEXT        NOT NULL,     -- PaymentAuthorized / PaymentCaptured / RefundSucceeded …
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;

-- V4__reconciliation.sql
CREATE TABLE settlement_reports (
    id             UUID PRIMARY KEY,
    psp            TEXT        NOT NULL,
    business_date  DATE        NOT NULL,
    file_sha256    TEXT        NOT NULL,
    version        INT         NOT NULL,
    status         TEXT        NOT NULL,   -- VALID / INVALID
    row_count      INT         NOT NULL,
    errors         JSONB,
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (psp, business_date, file_sha256),
    UNIQUE (psp, business_date, version)
);

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

CREATE TABLE recon_runs (
    id             UUID PRIMARY KEY,
    report_id      UUID        NOT NULL REFERENCES settlement_reports(id),
    snapshot_at    TIMESTAMPTZ NOT NULL,
    rules_version  TEXT        NOT NULL,
    status         TEXT        NOT NULL,   -- RUNNING / COMPLETED / FAILED
    summary        JSONB,                  -- counts and amounts per category
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE recon_items (
    run_id            UUID  NOT NULL REFERENCES recon_runs(id),
    item_no           INT   NOT NULL,
    category          TEXT  NOT NULL,
    psp_reference     TEXT,
    psp_operation_id  UUID,
    external_line_nos INT[],
    diff              JSONB,               -- field-by-field differences
    PRIMARY KEY (run_id, item_no)
);
```

notification-service database:

```sql
CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 7. API

Merchants authenticate with `Authorization: Bearer <api-key>`. **The merchant is always taken from the authenticated principal**; a merchant ID in the body can never override it. Operations endpoints use a separate ops key. Resources belonging to another merchant return 404, so existence is not leaked.

Every write requires an `Idempotency-Key` header.

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
| POST | `/ops/settlement-reports` | Upload a PSP settlement CSV | 201, or 200 for an identical file | 400 / 413 |
| POST | `/ops/recon-runs` | Reconcile a specific report version | 202 | 404 |
| GET | `/ops/recon-runs/{id}`, `/ops/recon-runs/{id}/items` | Run summary and item-level breaks | 200 | 404 |
| POST | `/ops/psp-operations/{id}/inquiry` | Trigger a PSP status inquiry | 202 | 404 |
| GET | `/actuator/health`, `/actuator/prometheus` | Health and metrics | 200 | |

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

Errors use RFC 7807 Problem Details with extra `code` and `traceId` fields, and optional `fieldErrors` and `retryAfterSeconds`.

| HTTP | code | What the client should do |
|---|---|---|
| 400 | VALIDATION_ERROR / INVALID_CSV | Fix the request; nothing was accepted and the key is not consumed |
| 401 / 403 | UNAUTHENTICATED / FORBIDDEN | Check credentials; do not retry |
| 404 | RESOURCE_NOT_FOUND | Does not exist or belongs to another merchant |
| 409 | IDEMPOTENCY_IN_PROGRESS | A request with the same key is still running; retry later with the same key |
| 409 | INVALID_STATE / REFUND_AMOUNT_EXCEEDED / DUPLICATE_REFERENCE | Read the current state before deciding |
| 422 | IDEMPOTENCY_KEY_REUSED | The key was used for a different request; do not switch keys blindly |
| 503 | TEMPORARILY_UNAVAILABLE | Retry with the **same** key; do not assume the first call was not accepted |

---

## 8. Idempotency

Key scope = merchant + operation (`scope`) + key.

1. Insert `idempotency_keys(..., status = 'IN_PROGRESS')`.
   - **Inserted:** new request, continue.
   - **Unique violation:** read the existing row —
     - different `request_hash` → **422**;
     - `COMPLETED` → replay the stored status and body with `Idempotent-Replayed: true`;
     - `IN_PROGRESS` → **409**, retry later.
2. In **one database transaction**: create or update the business object, write the `psp_operation`, write the outbox event, mark the key `COMPLETED` with the 202 response.
3. If validation fails and the transaction rolls back, the key rolls back too and can be reused.
4. The transaction makes **no PSP call**; the worker does that later (section 9), which keeps the transaction short.

**Three layers of de-duplication**

| Layer | Constraint | Protects against |
|---|---|---|
| Request | `PRIMARY KEY (merchant_id, scope, idem_key)` | Client retries of the same HTTP request |
| Business | `UNIQUE (merchant_id, merchant_reference)` | The same order submitted with a new key |
| Ledger | `UNIQUE (journal_entries.psp_operation_id)` | Duplicate webhooks, webhook racing an inquiry |

"Check, then insert" is not enough: under concurrency two requests can both see "not found". Only a database unique constraint guarantees a single winner. Idempotency keys are kept for 24 hours; business references and journal keys are kept permanently.

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
| 10 automatic attempts | Set `needs_review`, alert; **business state unchanged**, reservation not released |

If a worker dies mid-flight, the lease expires and another worker picks the operation up. The fixed request ID and the journal unique key make the repeat harmless.

### 9.2 Inbound webhooks (inbox)

1. Read the raw body; verify `X-PSP-Timestamp` (5-minute tolerance) and `X-PSP-Signature` = `HMAC-SHA256(secret, timestamp + "." + rawBody)` with a constant-time comparison (`MessageDigest.isEqual`). Failure → 401.
2. Schema validation failure → 400.
3. Insert into `inbox_events`; return 200 **only after the insert commits**. If the database is unavailable return 503 so the PSP redelivers. Never acknowledge first and persist later.
4. `(provider, event_id)` conflict: same payload → 200; different payload → 200, but record the conflict and alert without overwriting the original.
5. `InboxProcessor` validates the event against the `psp_operation` (request ID, merchant, type, amount, currency). Unknown operation or mismatched fields → `QUARANTINED`. Quarantined events never touch the ledger and never create payments.

Webhook fields: `event_id`, `event_type`, `psp_request_id`, `psp_reference`, `merchant_id`, `amount_minor`, `currency`, `status`, `occurred_at`, `resource_version`.

### 9.3 The money transaction (shared by webhooks and inquiries)

```
BEGIN
  SELECT … FROM payments WHERE id = ? FOR UPDATE        -- fixed lock order: payment → refund
  SELECT … FROM psp_operations WHERE id = ? FOR UPDATE
  already in the same final state  → mark inbox PROCESSED, return
  already in a different final state → QUARANTINE + alert; never roll back posted business
  older resource_version            → ignore
  update operation status and succeeded_at; update payment/refund status and captured/refunded/reserved
  capture or refund succeeded       → insert journal and postings (psp_operation_id unique)
  write outbox event; mark inbox PROCESSED
COMMIT      -- the deferred balance constraint is checked here
```

The two partial failures:
- **PSP succeeded, local commit failed:** nothing was written; the webhook redelivery or safety-net inquiry re-runs the transaction.
- **Local commit succeeded, the 200 never reached the PSP:** the PSP redelivers; the "same final state" branch returns without side effects.

### 9.4 mock-psp

A separate Spring Boot service with its own schema. Endpoints: submit an operation (idempotent on `psp_request_id`; same ID with different parameters is rejected), inquire by request ID, export a daily settlement CSV.

Fault injection (dev profile only):

| Fault | Verifies |
|---|---|
| `DECLINE` | Explicit failure; reservation released |
| `TIMEOUT_AFTER_COMMIT` | PSP succeeded but the response timed out → inquire, do not resubmit |
| `DROP_WEBHOOK` | Lost webhook → safety-net inquiry or reconciliation catches it |
| `DUPLICATE_WEBHOOK` | Same event ID ×10; different event IDs for the same outcome |
| `OUT_OF_ORDER` | A stale event cannot move state backwards |
| `DELAY_WEBHOOK` | Late delivery still converges |

---

## 10. Transactional outbox and Kafka

Writing to the database and publishing to Kafka cannot share a transaction. Without an outbox, either the database commits and the event is lost, or the event is published and the database rolls back. The outbox stores the event in the same transaction as the business change; a relay publishes it afterwards.

```sql
SELECT * FROM outbox_events
WHERE published_at IS NULL
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

- Publish to `payments.events` keyed by `aggregate_id` (payment ID) to preserve per-payment ordering.
- Set `published_at` after the broker acknowledges. This is **at-least-once**: a crash between publish and update re-sends the event, so consumers must be idempotent.
- Producer: `acks=all`, `enable.idempotence=true`.

**notification-service**
- Records `processed_events` and the delivery in one transaction; a duplicate `event_id` is skipped.
- Sends HMAC-signed webhooks to the merchant's `webhook_url` — the same scheme as inbound PSP webhooks, in the opposite direction.
- Three retries with exponential backoff, then `payments.events.dlq` (Spring Kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`). A production system would use a delivery table with scheduled retries, because merchant endpoints can be down for hours.
- Offsets are committed after successful processing.

End-to-end exactly-once is not realistic for business side effects; the design is **at-least-once delivery + idempotent consumers**, i.e. effectively-once. And **ledger correctness does not depend on Kafka**: if Kafka is down, balances stay correct and only notifications are delayed.

---

## 11. Settlement reconciliation

### 11.1 Settlement reports
- mock-psp exports one CSV per business day containing **successful captures and refunds only** (authorisations and voids move no money).
- Business day is UTC: `[D 00:00:00Z, D+1 00:00:00Z)`. Real UK operations often cut over on Europe/London time, where clock-change days are 23 or 25 hours long; UTC avoids that and the choice is recorded in an ADR.
- Columns: `psp_reference, psp_request_id, txn_type, merchant_id, amount_minor, currency, succeeded_at`.
- The whole file is validated on import. Missing columns, invalid amounts or timestamps, rows outside the business day, or more than 10 MB / 100,000 rows → the report is `INVALID` with line-level errors, and **no reconciliation result is produced**. Rows are never silently dropped.
- An identical file (SHA-256) returns the existing report. A corrected file becomes a new version; old versions are kept; each run is bound to one version.
- CSV exports escape cells beginning with `= + - @` to prevent spreadsheet formula injection.

### 11.2 Matching and classification

The internal side is read in a single `REPEATABLE READ` read-only transaction, so the whole run sees one consistent snapshot; `snapshot_at` is recorded. The internal set is every successful capture and refund whose `succeeded_at` falls in the business day, plus any local operation referenced by the report (to detect operations still PENDING locally).

Matching is one-to-one on `psp_reference`, falling back to `psp_request_id`. **Matching on amount and approximate time alone is forbidden.**

| Category | Rule | Handling |
|---|---|---|
| `MATCHED` | One-to-one; type, amount, currency, merchant and date agree | Recorded, no alert |
| `DUPLICATE_EXTERNAL` | Same PSP reference on several rows of one report | Whole group is a break; no row is picked to match |
| `MISSING_INTERNAL` | In the report, not found locally | Keep the external record, trigger an inquiry; **never create business from the report** |
| `MISSING_EXTERNAL` | Succeeded locally that day, absent from the report | Check for dropped rows, report version and success date |
| `AMOUNT_MISMATCH` | Matched, but amount, merchant or date differ | Field-level diff; **never overwrite local amounts** |
| `STATUS_MISMATCH` | Succeeded at the PSP, PENDING or FAILED locally | Trigger an inquiry; PENDING resolves through normal posting; conflicting final states need review |

The primary category follows the order duplicate → unmatched → field conflict → status conflict, so every external row and every internal record ends up in exactly one category and the counts are disjoint.

A separate **ledger integrity check** runs regardless of the report: exactly one journal per successful capture or refund, every journal balanced, all postings sum to zero, and any balance cache agrees with postings. Any failure is a blocking alert.

### 11.3 Results
- Completed runs are **immutable**. After a fix, a new run is created and old runs remain for comparison.
- The summary shows raw external row count, unique transaction count, total captured, total refunded, net, and count and amount per category. **Equal net totals never replace item-level matching.**
- Reconciliation only reports; it **never mutates the ledger**. Fixes go through the normal path (inquiry → money transaction).

---

## 12. Testing

| Type | What | Tools |
|---|---|---|
| Unit | Every state transition; fee rounding boundaries; every journal balances; reconciliation rules | JUnit 5, AssertJ |
| Integration | Full flows against real PostgreSQL; deferred constraint, unique constraints and append-only triggers actually fire | Testcontainers |
| Property-based | ≥ 1,000 random payment / capture / partial-refund sequences with duplicate webhooks and recoverable faults; all invariants checked after each; seeds kept | jqwik |
| Messaging | Rolled-back transaction leaves no outbox row; relay restart re-publishes; duplicate delivery processed once; poison message reaches DLQ | Testcontainers Kafka, Awaitility |
| Fault injection | Acceptance tests below | mock-psp, WireMock |
| CI | Full suite on every push; build fails below 80% line coverage | GitHub Actions, JaCoCo |

### Acceptance tests

Each test sets up its own data and is repeatable. Tests assert **both** the local database and mock-psp state; an HTTP success is never taken as proof of the money effect.

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

Four single-fault report variants — duplicated P1 row, missing P3 row, P3 amount 5100, an unknown transaction — each ship with their own expected-result file.

---

## 13. Observability

**Metrics** (Micrometer)
- `payments_total{operation, outcome}`, `payment_request_duration_seconds` (histogram)
- `idempotency_replays_total`
- `psp_operations_pending`, `psp_operation_oldest_pending_seconds`, `psp_operations_needs_review`
- `psp_call_duration_seconds{operation, outcome}`, `psp_call_errors_total`
- `inbox_quarantined_total`, `webhook_signature_failures_total`
- `outbox_unpublished_events`, `outbox_publish_lag_seconds`
- `refund_reserved_minor`
- `recon_breaks{category}`, `recon_run_duration_seconds`
- `ledger_invariant_violations` (must always be 0)

**Dashboard:** throughput, p99 latency, error rate, oldest pending operation, outbox backlog, consumer lag, reconciliation breaks.

**Logs:** JSON with `traceId`, `paymentId`, `pspRequestId`, `idempotencyKey`. API keys, signing secrets and full auth headers are never logged.

**Alerts** (Prometheus rules in `infra/prometheus/alerts.yml`): ledger invariant violation (blocking); oldest pending > 5 min; any `needs_review`; any quarantined event; outbox backlog for 5 min; new DLQ message; failed reconciliation run or new breaks.

---

## 14. Load testing (k6)

1. **Steady traffic:** create → capture with mock-psp in instant-success mode; ramp up; record throughput and p99.
2. **Retry storm:** the same idempotency key 10,000 times in a short window; exactly one capture.
3. **Mixed:** 80% reads, 20% writes across create, capture, refund and void.
4. **Reconciliation throughput:** 100,000-row report from validation to completed run.

Webhook-to-commit latency is measured separately from API acceptance latency. Results are published with machine specification, data volume and the commit hash of the scripts. Only measured numbers are reported.

---

## 15. Deployment

- **Local:** Docker Compose — PostgreSQL, Kafka, Prometheus, Grafana and the three services.
- **AWS (Terraform):** default VPC; ECR; ECS Fargate for the three services; RDS PostgreSQL (db.t4g.micro); CloudWatch Logs; SSM Parameter Store for database credentials and signing secrets.
- **Messaging on AWS:** either a single Kafka task on ECS for demos, or the `SqsEventPublisher` adapter with SNS + SQS. MSK is avoided for cost.
- The AWS stack is applied for demos and destroyed afterwards; a budget alert guards spend.

---

## 16. Optional: reconciliation assistant

When reconciliation reports breaks, structured facts (category, field diff, operation timeline, latest inquiry result) are given to an LLM, which drafts likely causes and next steps.

Guardrails: the LLM can only *propose* an inquiry, which a human must confirm; it can never modify the ledger. Inputs contain internal IDs and amounts only. Output is a fixed JSON schema, validated before display. A small evaluation set — the four report variants plus the lost-webhook case — checks whether the diagnosis is correct.

---

## 17. Repository layout

```
ledgerpay/
├── README.md
├── docs/
│   ├── design.md             # this document
│   └── adr/
├── common/                   # event contracts, Money, HMAC utilities
├── payment-service/
│   └── src/main/java/.../
│       ├── api/              # controllers, DTOs, ProblemDetail, auth
│       ├── payment/          # Payment, PaymentStatus, state machine
│       ├── refund/           # Refund, reservation
│       ├── ledger/           # accounts, posting rules, integrity checks
│       ├── psp/              # PSP client, PspOperationWorker, inquiry
│       ├── webhook/          # verification, inbox, InboxProcessor
│       ├── reconciliation/   # CSV import, matching, classification
│       ├── outbox/           # OutboxRelay, EventPublisher and adapters
│       └── idempotency/
├── notification-service/
├── mock-psp/                 # simulated PSP, fault injection, settlement export
├── load-tests/               # k6 scripts
├── demo/                     # demo data, report variants, expected results
├── infra/
│   ├── docker-compose.yml
│   ├── prometheus/alerts.yml
│   └── terraform/
└── .github/workflows/ci.yml
```

### Planned ADRs

| ADR | Decision |
|---|---|
| 0001 | Money as integer minor units; HALF_UP fee rounding |
| 0002 | Idempotency via database unique constraints, three layers |
| 0003 | No ledger postings on authorisation or void |
| 0004 | Unknown outcomes: inquire before any retry, fixed request IDs |
| 0005 | Refund reservation with pessimistic locking |
| 0006 | Transactional outbox; at-least-once delivery with idempotent consumers |
| 0007 | Reconciliation never mutates the ledger; UTC business days |

Each ADR records context and constraints, the decision, rejected alternatives, trade-offs and the tests that cover it.

---

## 18. Roadmap

| Milestone | Contents | Exit criteria |
|---|---|---|
| **M1 — Ledger and core flow** | Multi-module Maven build, Compose, Flyway V1–V2; Money, fees, state machine; ledger with deferred balance constraint and append-only triggers; idempotency; mock-psp happy path; PspOperationWorker; webhook inbox; money transaction | AT-01 – AT-05 |
| **M2 — Failure handling and events** | Fault injection; inquiry-first retries and backoff; partial refunds with reservation; outbox, relay, Kafka; notification-service with DLQ; CI with coverage gate; ADR 0001–0006 | AT-06 – AT-14; `docker compose up` runs the full flow |
| **M3 — Reconciliation and observability** | Settlement export and import; classification; ledger integrity check; demo data and variants; metrics, dashboard, alert rules; ADR 0007 | AT-15 – AT-19; recorded end-to-end demo |
| **M4 — Performance and cloud** | k6 scenarios; Terraform deployment to AWS; optional SQS adapter and reconciliation assistant | Published load-test results; deployment evidence |

Idempotency, inquiry-first retries, refund reservation, database-enforced ledger invariants and reconciliation are the core of the project and are not traded away for schedule.
