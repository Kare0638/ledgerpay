# ADR 0005 — Refund reservation with pessimistic locking

**Status:** accepted · **Date:** 2026-10-05 · **Issue:** [#12](https://github.com/Kare0638/ledgerpay/issues/12)

## Context

A captured payment can be refunded in parts, by requests that may arrive at the same time. Each refund is confirmed by the PSP later, asynchronously, and its outcome may stay unknown for minutes (ADR 0004).

Constraints:

- Refunds must never add up to more than was captured, whatever their order, timing or outcome (design §2, requirement 2).
- "Is there enough left?" is a read of an aggregate (captured − refunded − in flight), then a write. Two requests that both read before either writes would both pass.
- A refund whose outcome is unknown may have been paid out. Its amount cannot be offered to another refund until the PSP answers.
- No PSP call happens inside a database transaction (ADR 0002).

## Decision

**The refundable amount is `captured_minor − refunded_minor − refund_reserved_minor`, and accepting a refund reserves its amount in the same transaction that checks it.** In one transaction:

1. `SELECT … FOR UPDATE` the payment.
2. A merchant reference already used for the same refund returns that refund. This comes before any amount check, so a retry is not refused because its own reservation used up the balance. The same reference with another payment or amount is 409 `DUPLICATE_REFERENCE`.
3. The payment must be CAPTURED (409 `INVALID_STATE`), and the amount at most the refundable amount (409 `REFUND_AMOUNT_EXCEEDED`).
4. Insert the PENDING refund, add its amount to `refund_reserved_minor`, and write its pending REFUND operation.

**The PSP's answer is applied by the money transaction** (design §9.3), under the same payment lock:

| Outcome | Reservation | `refunded_minor` | Ledger | Refund | Outbox |
|---|---|---|---|---|---|
| Succeeded | released | + amount | refund journal: merchant_payable +, psp_receivable − | SUCCEEDED | `RefundSucceeded` |
| Failed | released | unchanged | nothing | FAILED | `RefundFailed` |
| Unknown | **kept** | unchanged | nothing | PENDING | nothing |

The payment's status stays CAPTURED throughout. How much has been refunded is reported separately, as `refundSummary` (NONE, PARTIAL or FULL), derived from the amounts.

**The database is the backstop:** `CHECK (refunded_minor + refund_reserved_minor <= captured_minor)`, `CHECK (refunded_minor >= 0 AND refund_reserved_minor >= 0)`, and one journal per operation (`UNIQUE (psp_operation_id)`).

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Optimistic locking: compare `version`, retry the command on conflict | The command also inserts a refund, an operation and an idempotency record. Retrying all of that correctly is harder than waiting for a row lock, and contention on one payment is low: a merchant rarely sends several refunds for one payment at once. |
| `SERIALIZABLE` isolation | Correct, but it fails the losing transaction with a serialization error that every caller must retry, and it widens what conflicts beyond this one row. |
| Only the CHECK constraint | It stops the overrun, but as a constraint violation: the API would answer 500 instead of 409 `REFUND_AMOUNT_EXCEEDED`. With the lock removed, AT-11 fails exactly that way (see Tests). |
| Sum the refunds table instead of keeping amounts on the payment | An aggregate over rows still needs a lock to be safe, and the amounts on the payment are what the CHECK constraint can see. |
| Release the reservation on timeout | A timed-out refund may have been paid out. Freeing its amount could refund the same money twice. |

## Trade-offs

- Refunds of one payment are serialised. That is intended. Refunds of different payments do not wait for each other.
- A refund whose outcome stays unknown holds its amount until an inquiry or a webhook settles it, or until a human resolves it (`needs_review`, ADR 0004). The merchant sees it as reserved, not refundable.
- The fee is not returned on a refund (design §5.5), so after a full refund the merchant owes the platform the fee: `merchant_payable` ends at +100p for £100 at 1 %.

## Tests

- `RefundIntegrationTest`:
  - **AT-10**: £30 then £70 → two refund journals, `refundSummary` FULL, the payment's net on `psp_receivable` is 0.
  - **AT-11**: £70 refundable, £50 and £40 at once → one 202 and one 409 `REFUND_AMOUNT_EXCEEDED`. With the payment read without `FOR UPDATE`, the test failed in each of three runs: both pass the check and the CHECK constraint rejects the second, as a 500.
  - **AT-12**: an unknown outcome keeps the reservation, and a failure then releases only it. A success after a timeout releases it and books once, even when the outcome arrives twice.
  - Also: over-limit, refunds before capture, validation, replays, references reused with other amounts, and other merchants' payments and refunds (404).
- `RefundAcceptanceTest` (real mock-psp):
  - **AT-10**: the whole flow; mock-psp holds two successful refunds.
  - **AT-12**: `TIMEOUT_AFTER_COMMIT` on a refund keeps the reservation until the inquiry books it once; `DECLINE` releases it.
