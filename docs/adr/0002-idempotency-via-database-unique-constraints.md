# ADR 0002 — Idempotency via database unique constraints, in three layers

**Status:** accepted · **Date:** 2026-09-30 · **Issue:** [#5](https://github.com/Kare0638/ledgerpay/issues/5)

## Context

Clients retry. A merchant's HTTP client times out after the payment was already created and sends the request again; a load balancer retries on a dropped connection; a user double-clicks "Pay". Each retry must not create a second payment, and above all must not move money twice.

The retries arrive **concurrently** as often as sequentially: AT-02 fires 50 identical requests at the same instant. Any scheme of the form "look for an existing request, and if there is none, insert one" fails here, because every request can see "none" before any of them inserts.

Constraints:

- The write path must not call the PSP inside the transaction (design §3, principle 2), so the transaction stays short.
- A request that fails validation or is rejected (400, 409 `DUPLICATE_REFERENCE`) must not consume the key (design §7).
- Idempotency must also hold across different keys for the same order, and across webhooks and inquiries for the same PSP operation. One mechanism cannot cover all three.

## Decision

Three layers, each backed by a **database unique constraint**, because only the database can pick a single winner among concurrent writers:

| Layer | Constraint | Protects against | Implemented in |
|---|---|---|---|
| Request | `PRIMARY KEY (merchant_id, scope, idem_key)` on `idempotency_keys` | client retries of the same HTTP request | `IdempotentExecutor` |
| Business | `UNIQUE (merchant_id, merchant_reference)` on `payments` | the same order sent again with a new key | `Payments.insertIfReferenceFree` |
| Ledger | `UNIQUE (psp_operation_id)` on `journal_entries` | duplicate webhooks, a webhook racing an inquiry | `Ledger.post` (ADR 0003, #4) |

**Request layer flow.** The key row and the business writes share **one transaction**:

1. `INSERT … ON CONFLICT DO NOTHING` claims `(merchant, scope, key)` with the SHA-256 of the canonical request and status `IN_PROGRESS`.
2. If the key already exists:
   - a different request hash → **422** `IDEMPOTENCY_KEY_REUSED`;
   - `COMPLETED` → replay the stored status and body with `Idempotent-Replayed: true`;
   - `IN_PROGRESS` → **409** `IDEMPOTENCY_IN_PROGRESS`.
3. Otherwise, in the same transaction: insert the payment, the `AUTHORIZE` PSP operation and the outbox event, then mark the key `COMPLETED` with the 202 response.

A concurrent request with the same key blocks on the primary key at step 1 until the first transaction ends. If that transaction commits, the waiter reads the completed row and replays it. If it rolls back, the waiter's insert succeeds and it becomes the owner. So with one transaction, `IN_PROGRESS` is never visible to other requests, and in practice concurrent duplicates get a replay rather than a 409. The 409 branch is kept for a future write path whose work spans more than one transaction.

**Canonical request hash.** The hash is taken over the **parsed** request, re-serialised with sorted properties. Whitespace, field order and unknown fields (such as a `merchantId` in the body, which is ignored) therefore do not make a "different" request.

**Business layer.** `INSERT … ON CONFLICT (merchant_id, merchant_reference) DO NOTHING`. If the reference is already taken:

- the same amount → return the existing payment, with no new PSP operation or event;
- a different amount → **409** `DUPLICATE_REFERENCE`, which rolls back the whole transaction including the key.

`ON CONFLICT` is used instead of catching a unique violation because in PostgreSQL a failed statement aborts the transaction.

**Scope and retention.** Keys are scoped per merchant and per operation (`scope = 'POST /v1/payments'`), so two merchants can use the same key. Keys are meant to be kept for 24 hours, while business references and journal keys are permanent. The clean-up job is not built yet.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Check, then insert (`SELECT` first) | Under concurrency every request sees "not found". AT-02 would create up to 50 payments or hit unique violations as 500s. |
| Claim the key in its own transaction, then do the work in a second one (the literal reading of design §8 step 1) | It adds a window in which a crash leaves an orphaned `IN_PROGRESS` key that needs expiry logic. With no PSP call in the write path, the whole unit of work is short enough for one transaction. |
| An in-memory or Redis lock | It is a second source of truth, it is lost on restart, and it still needs the database constraint as a backstop, so the constraint alone is simpler. |
| Hashing the raw body bytes | Harmless reformatting by a client, such as whitespace or field order, would turn a retry into a 422. |
| Only the request layer | A merchant retrying with a *new* key (for example after losing the first response) would create a second payment for the same order. The business layer catches that. |

## Trade-offs

- Concurrent duplicates wait on a row lock for the duration of the first transaction. That transaction is short and makes no network calls, so the wait is milliseconds.
- The stored response contains no `traceId`. Each response, first or replayed, gets the **current** request's trace ID, matching its `X-Trace-Id` header (design §7). A replay is its own request and must be traceable in the logs as such.
- A 422 or 409 is not stored, so it is not replayed. The client is expected to read the current state rather than retry blindly (design §7).

## Tests

`PaymentApiIntegrationTest` runs over real HTTP and PostgreSQL, with filters, validation and error handling included:

- **AT-02**: 50 threads released together by a `CountDownLatch` with one key → all 202, one payment ID, 49 replays, and exactly one payment, one `AUTHORIZE` operation and one outbox event.
- **AT-03**: the same key with the amount changed from 10000 to 12000 → 422, and the original payment is unchanged.
- **AT-04**: a new key with the same reference returns the existing payment when the parameters match, and 409 `DUPLICATE_REFERENCE` when they differ; the rejected key can then be reused.
- A replay returns the same body with `Idempotent-Replayed: true`, except for `traceId`, which is the replay's own and matches its header. The stored body has no `traceId`. Reformatted JSON counts as the same request, and keys are scoped per merchant.
- 400 validation errors and 409 conflicts do not consume the key.
- `RequestHashTest` checks that the hash is stable and that it changes when any field changes.
