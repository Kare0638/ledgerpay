# Reconciliation demo

One merchant, one business day, and a real break: a capture whose webhook is lost, found by reconciliation and fixed through the normal path ([design §12](../docs/design.md#demo-data-set), [ADR 0007](../docs/adr/0007-reconciliation-never-mutates-the-ledger-utc-business-days.md)).

```bash
demo/run.sh            # the whole story, every number checked against demo/expected/
demo/run.sh --pause    # waits for Enter before each step, for a recorded walkthrough
```

Docker with Compose v2, curl 7.76+ and bash 4.4+. It runs its own Compose project, `ledgerpay-recon-demo`, on fresh volumes each time, so the day's settlement report holds only this story; stop the main stack first, since both use the same ports. A run takes about a minute after the first build.

## The day

Everything goes through the API and mock-psp's fault injection. No row is written by hand, so the break is one the system really produced.

| | What happens | What it shows |
|---|---|---|
| P1 | £100 captured; R1 £30 refunded | The happy path |
| | Two more refunds of P1 at once, £50 and £40, with £70 left | The reservation lets one in and refuses the other with 409 `REFUND_AMOUNT_EXCEEDED` (AT-11). The PSP declines the one let in, and its reservation is released, so R1 stays the only refund |
| P2 | £200 captured at the PSP, webhook lost (`DROP_WEBHOOK`) | Ours stays `CAPTURE_PENDING` with no journal |
| P3 | £50 captured, the outcome sent 12 times (`DUPLICATE_WEBHOOK`) | 3 distinct event IDs in the inbox, 1 journal (AT-07) |
| P4 | £40 authorisation declined (`DECLINE`) | No journal; not in the report, as it moved no money |

Then:

1. mock-psp's settlement report for the day is imported: four rows, P1, R1, P2 and P3.
2. **Run 1**: P2 is a `STATUS_MISMATCH`. The report nets to 32000; our books to 12000, as P2 is still pending. Nothing is booked from the report.
3. **The fix**: an inquiry about P2's capture, which the money transaction books once.
4. **Run 2** of the same report: all `MATCHED`, captures 35000, refunds 3000, net 32000. Run 1 is unchanged.
5. **The books**: receivable 32000 = merchant payable 31650 + fee revenue 350, and the ledger integrity check passes.
6. **Four single-fault variants** of the report, each a new version reconciled against the fixed books.

## Expected results

Each run is compared with a file in [`expected/`](expected/), in SQL inside the stack's PostgreSQL, so the host needs no JSON tool. Items are named by their labels (P1, R1, P2, P3, UNKNOWN), which the script maps to the PSP references of the run. Every key but `description` must match exactly:

- `items`: each transaction's category;
- `categories`: the categories with any items, and how many;
- `external` / `internal`: captured, refunded and net of the report and of our books;
- `diffs`, where given: the field-level diff of each `AMOUNT_MISMATCH`.

| File | Report | Expected |
|---|---|---|
| [`before-fix.json`](expected/before-fix.json) | The day's report, P2 not yet booked | P2 `STATUS_MISMATCH`, the rest `MATCHED` |
| [`after-fix.json`](expected/after-fix.json) | The same report, after the inquiry | All `MATCHED`; both sides 35000 / 3000 / 32000 |
| [`duplicate-p1.json`](expected/variants/duplicate-p1.json) | P1's row twice | P1 `DUPLICATE_EXTERNAL` |
| [`missing-p3.json`](expected/variants/missing-p3.json) | P3's row removed | P3 `MISSING_EXTERNAL` |
| [`amount-p3.json`](expected/variants/amount-p3.json) | P3 at 5100 | P3 `AMOUNT_MISMATCH`, diff 5100 / 5000 |
| [`unknown-transaction.json`](expected/variants/unknown-transaction.json) | An extra £15 capture we never made | `MISSING_INTERNAL` |

A mismatch stops the script and prints, key by key, what was expected and what the run produced.

## Recording a walkthrough (3–5 minutes)

`demo/run.sh --pause` stops before each step. The narration follows the steps:

1. **Setup** (20 s): one merchant, 1 % fee, a fresh stack. Every payment goes through the API; faults are mock-psp's.
2. **P1 and the refunds** (40 s): £100 captured, £30 refunded. Two refunds at once against £70: one 202, one 409, because the reservation is taken under the payment's lock. The accepted one is declined by the PSP and its reservation released.
3. **P2, the lost webhook** (30 s): mock-psp says SUCCEEDED, we say CAPTURE_PENDING. A timeout or a lost webhook is never treated as a failure.
4. **P3 and P4** (30 s): twelve deliveries, three event IDs, one journal; a declined authorisation books nothing.
5. **Run 1** (40 s): the report's four rows, the items table, P2 as STATUS_MISMATCH. Equal nets would not hide it: matching is item by item.
6. **The fix and run 2** (40 s): the inquiry books P2 once; run 2 all MATCHED; run 1 kept unchanged.
7. **The books** (30 s): receivable 32000 = payable 31650 + revenue 350; the integrity check passes.
8. **Variants** (30 s): duplicate, missing, amount, unknown, each its own category.
