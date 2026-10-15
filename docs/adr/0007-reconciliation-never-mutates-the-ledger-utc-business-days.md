# ADR 0007 — Reconciliation never mutates the ledger; UTC business days; reports kept as versions

**Status:** accepted · **Date:** 2026-10-14 · **Issues:** [#17](https://github.com/Kare0638/ledgerpay/issues/17), [#18](https://github.com/Kare0638/ledgerpay/issues/18)

## Context

Once a day the PSP sends a settlement report: every capture and refund it settled, as CSV. Reconciliation compares it with our books, item by item, and reports the breaks (design §11).

Constraints:

- The report is outside input. It can be late, corrected, sent twice, truncated or simply wrong. Our ledger is the source of truth for what *we* recorded; the report is evidence of what the *PSP* recorded. Neither may silently overwrite the other.
- A row dropped on import turns into a false `MISSING_EXTERNAL`, or, worse, hides a real break. A file that is half imported is worse than one rejected.
- "The day" must mean the same thing to mock-psp's export, the import's validation and the reconciliation's snapshot, or a transaction near midnight shows up as a break on both days.
- An auditor or an operator must be able to say which file a reconciliation result came from, long after a corrected file arrived.

## Decision

**A business day is the UTC day**, `[D 00:00:00Z, D+1 00:00:00Z)`, by the PSP's success time (`succeeded_at`). mock-psp's export, the import's validation and the reconciliation snapshot all use this one definition.

**A report is validated whole and is either VALID with every row or INVALID with none.** `SettlementCsvParser` checks the header (exactly the seven columns), then every field of every row: strict UTF-8, positive whole-number amounts, ISO 4217 currencies, ISO-8601 timestamps ending in `Z` inside the business day, no blank lines, no control characters, no spaces around references. All errors are collected with their line and column, and one is enough to make the file INVALID. A file over 10 MB is refused with 413 before anything is stored; one over 100,000 rows is INVALID.

**Every new file is kept as a version, and versions never change.** The SHA-256 of the bytes identifies a file: the same file again returns the stored report (200, or the same 400 for an INVALID one). Any other file for the PSP and day becomes the next version, taken under a transaction-scoped advisory lock on the PSP and day so that concurrent uploads cannot share a number. INVALID versions are kept too, with their errors (the first 1,000; all are counted) but no rows, so the history shows what was received and rejected. Triggers make `settlement_reports` and `settlement_rows` append-only, as the journals are.

**Reconciliation only reads and reports** (#18). A run is bound to one VALID report version and one consistent snapshot of the books, and its result is immutable. It never posts, reverses or edits a journal, and never changes an operation's status. A break is resolved through the normal path: an inquiry to the PSP, whose answer goes through the money transaction like any webhook (ADR 0004). A new run then shows the result, and the old one stays for comparison.

**CSV that a person may open in a spreadsheet is formula-escaped.** A cell beginning with `= + - @`, a tab or a carriage return gets a leading `'`. So does one that already begins with quotes before such a character, so the escape can always be undone exactly; the import does that, and `merchant_id` `=m_1` round-trips as `=m_1`.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| Europe/London business days | Matches how many UK operations cut over, but clock-change days are 23 or 25 hours long, and every boundary check needs a time-zone rule. UTC keeps the day a fixed interval; a London cut-over can be added at the edge if a real PSP needs it. |
| Import the valid rows and report the bad ones | Reconciliation would then run on a partial file and report every skipped row as missing on the PSP's side, or miss a break hidden in one of them. A whole-file decision gives one clear outcome: fix the file and upload it again. |
| Replace the report when a corrected file arrives | Earlier results would point at data that no longer exists, and nobody could show what the first file said. |
| Reconciliation applies the PSP's amount or status to the books | Books changed by a file nobody has checked; a wrong report would corrupt the ledger. The inquiry path asks the PSP itself, and posts through the same idempotent transaction as a webhook. |
| Return 201 with status INVALID | A client checking only the status code would take a rejected file for an accepted one. 400 `INVALID_CSV` says it was not accepted; the stored version and its ID are in the problem body. |
| Store rows of INVALID reports | `settlement_rows` has typed columns, and a bad row has, by definition, a field that does not fit. The errors say what was wrong; the next version holds the corrected rows. |

## Trade-offs

- A single bad row holds up the whole day's reconciliation until a corrected file arrives. That is the intent: a partial run would be wrong in ways that are harder to see.
- INVALID versions take version numbers, so a day can be at version 3 with only one VALID report.
- mock-psp exports the rows *so far* for a day that has not ended. A real PSP publishes after its cut-off; the demo reconciles a closed day.
- Escaping changes what a cell looks like in the raw file (`'=m_1`). Our import undoes it; another consumer would see the quote.

## Tests

- `CsvTest` (common): quoting, escaping and parsing, including line numbers, blank lines and broken quotes, and a jqwik property that any list of values survives writing and parsing (after unescaping).
- `SettlementReportTest` (mock-psp): only successful captures and refunds of the UTC day, including `00:00:00Z` and excluding the next midnight; success order; formula escaping; a missing or invalid date is a 400.
- `SettlementCsvParserTest`: every validation rule, with the errors' lines and columns.
- `SettlementReportIntegrationTest`:
  - a VALID report and its rows; the same file again is the same report; an INVALID one is kept without rows;
  - a corrected file is the next version, and earlier versions are kept;
  - concurrent uploads take distinct versions, or store one file once;
  - the size limit, with and without `Content-Length`;
  - append-only rows and reports.
  - With the advisory lock removed, both concurrency tests fail. Without the re-check of the hash under the lock, the one-file test fails. With rows kept despite errors, the INVALID test fails.
- `SettlementReportAcceptanceTest` (real mock-psp), **AT-16**: the day's export imports as VALID with every row. Copies with a bad amount, a missing column or a row from the previous day are each rejected whole, with the line and column, and store no rows.
- That a run on an INVALID report gives no result, and that a run never changes the ledger, are tested with the reconciliation engine (#18).
