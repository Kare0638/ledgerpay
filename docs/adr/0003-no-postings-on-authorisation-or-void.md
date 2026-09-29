# ADR 0003 — No ledger postings on authorisation or void

**Status:** accepted · **Date:** 2026-09-29 · **Issue:** [#4](https://github.com/Kare0638/ledgerpay/issues/4)

## Context

A card payment goes through two steps. **Authorisation** asks the issuer to hold funds on the cardholder's account; nothing is transferred. **Capture** asks for the held funds to be moved; this is when the PSP becomes liable to pay us at settlement. A **void** releases an authorisation that was never captured.

The ledger (design §5.5) has three accounts: `psp_receivable` (asset: owed to us by the PSP), `merchant_payable` (liability: owed by us to the merchant) and `fee_revenue`. The question is whether authorisation and void should also produce journals, for example by moving the amount into an "authorised" or "pending" account.

Constraints:

- Reconciliation matches the ledger item by item against the PSP settlement report, and that report contains only captures and refunds (design §11.1).
- Every journal is tied to exactly one successful PSP operation by `UNIQUE (psp_operation_id)`. That key is the last line of defence against double booking.
- Journals are append-only. A journal that later turns out to be wrong can only be corrected by a reversal journal.

## Decision

Only operations that **move money** are posted:

| PSP operation | Journal |
|---|---|
| Authorise | **none**: funds are held at the issuer, and nothing is owed between us, the PSP or the merchant |
| Void | **none**: it releases a hold that was never booked |
| Capture | `psp_receivable` +amount, `merchant_payable` −(amount − fee), `fee_revenue` −fee |
| Refund | `merchant_payable` +amount, `psp_receivable` −amount (the fee is not returned) |
| Any explicit failure | none |

`EntryType` therefore has only `CAPTURE` and `REFUND`, and the database rejects any other value. The authorisation state lives on the payment (`AUTHORIZED`, with `amount_minor`), not in the ledger.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Post authorisations to a memo account such as `authorised_funds` and reverse on capture or void | Every authorisation would need two journals, one to book it and one to reverse it. A lost void webhook would leave a phantom balance that reconciliation cannot explain, because holds never appear in the settlement report. It also doubles the journals that the one-journal-per-operation key has to police. |
| Post on authorisation and treat capture as a reclassification | A declined capture or an expired authorisation would then need a reversal journal for money that never moved. The ledger would describe intentions, not facts. |
| Off-balance-sheet "memo" postings that do not have to sum to zero | They break invariant 1 (every journal sums to zero) and the ledger-wide zero-sum check. Holds are not money, so they belong on the payment, not in the books. |

## Trade-offs

- The ledger cannot answer "how much is authorised but not yet captured?". That is an operational question, and it is answered from `payments` (status `AUTHORIZED`, `amount_minor`) or `psp_operations`, both of which are already the source of truth for in-flight work.
- A capture journal is posted only when the PSP **confirms** the capture, not when we request it. Between request and confirmation the payment is `CAPTURE_PENDING` with no ledger impact. This matches design §9: a timeout is not a success.

## Tests

- `PostingRulesTest` covers the capture and refund rules, including AT-01: £100 at 1% gives +10000 / −9900 / −100. It also covers zero-fee and full-fee captures, where no zero posting is written.
- `PostingRulesPropertyTest` (jqwik) checks that every capture and refund journal has at least 2 postings and sums to zero, across the full amount range and every fee rate.
- `LedgerIntegrationTest` (Testcontainers PostgreSQL) covers:
  - AT-01 end to end, and capture followed by refunds of £30 and £70;
  - that the database rejects unbalanced journals and journals with 0 or 1 postings at commit, a second journal for the same operation, any UPDATE, DELETE or TRUNCATE, postings appended to a committed journal, and postings whose currency differs from their account's.
- `EntryType` has no `AUTHORIZE` or `VOID` value, and `journal_entries.entry_type` has a CHECK constraint allowing only `CAPTURE` and `REFUND`.
