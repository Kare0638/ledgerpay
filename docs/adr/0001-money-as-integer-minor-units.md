# ADR 0001 — Money as integer minor units; HALF_UP fee rounding

**Status:** accepted · **Date:** 2026-09-28 · **Issue:** [#2](https://github.com/Kare0638/ledgerpay/issues/2)

## Context

Every amount in LedgerPay ends up in a ledger posting, a PSP request or a settlement report row, and the three must agree to the penny. Reconciliation (design §11) matches item by item, so a one-penny drift from binary floating point or inconsistent rounding shows up as a break, and a ledger journal that is off by a penny cannot commit at all (design §5.5, invariant 1).

Constraints:

- Single currency, GBP, with two decimal places. Payments range from 1p to 100,000,000p (£1,000,000).
- Ledger postings are signed (debit positive, credit negative), and a merchant payable balance can be negative after a full refund, because the fee is retained.
- The platform fee is `amount × fee_bps / 10000` with `fee_bps` from 0 to 10,000. It is almost never a whole number of pence, so a rounding rule is needed.

## Decision

1. **Amounts are `long` minor units** (`1050` = £10.50), carried in an immutable `Money(long minor, Currency currency)` record in `common`. The database uses `BIGINT`, and JSON uses an integer `amountMinor`. `double`, `float` and `BigDecimal` are not used anywhere on a money path.
2. **Money is signed.** `Money.positive(...)` is the entry point wherever only a positive amount is valid (payment and refund requests). The payment range check (1 to 100,000,000) is a payment-domain rule and is not part of `Money`.
3. **Currencies never mix.** `plus` and `minus` throw `CurrencyMismatchException` if the currencies differ.
4. **Overflow fails loudly.** Arithmetic uses `Math.addExact`, `Math.subtractExact`, `Math.negateExact` and `Math.multiplyExact`, so an overflow throws `ArithmeticException` and never wraps silently.
5. **Fees round HALF_UP to the penny, in `long` arithmetic:** `(amount × fee_bps + 5000) / 10000`. For a non-negative amount, adding half the divisor before flooring integer division is exactly HALF_UP. The largest intermediate value is 100,000,000 × 10,000 = 10¹², far below `Long.MAX_VALUE` (≈ 9.2 × 10¹⁸).
6. **The fee is not returned on refund** (design §2), so fee rounding happens once per capture and never has to be reversed.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| `double` / `float` | 0.1 + 0.2 ≠ 0.3 in binary floating point. It drifts silently and breaks penny-exact reconciliation. |
| `BigDecimal` everywhere | Correct, but `equals` depends on scale (`1.0` ≠ `1.00`), it allocates on every operation, and it pushes rounding decisions into every call site. Integers make "a whole number of pence" a property of the type. |
| Rounding with `Math.round(amount * bps / 10000.0)` | It goes through a `double`, and `Math.round` rounds negative halves towards positive infinity. |
| HALF_EVEN (banker's rounding) | It is unbiased over many values, but a fee is a single figure per capture that merchants check by hand; HALF_UP is the rule they expect. |
| Truncating (always rounding down) | It systematically under-charges and the bias accumulates. |
| A money library (e.g. Joda-Money, JSR 354) | It is more than this single-currency scope needs, and it hides exactly the arithmetic this project wants to make explicit and test. |

## Trade-offs

- Minor units assume a fixed number of decimal places per currency. Supporting a currency with 0 or 3 decimals, or FX, would need a scale per currency. Both are out of scope (design §2).
- HALF_UP carries a small upward bias on exact halves (x.5p). At 100 bps that happens only when the amount ends in 50, and it is accepted for predictability.

## Tests

- `MoneyTest`: value semantics, `positive` rejects 0 and negative values, signed ledger amounts, currency mismatch, overflow on `plus`, `minus` and `negate`.
- `FeesTest`: the boundaries 1p, 49p, 50p, 149p, 150p, 151p and 100,000,000p; `fee_bps` values 0, 4999, 5000 and 10000; AT-01's £100 at 1% = 100p; rejection of `fee_bps` outside 0 to 10,000 and of negative amounts; overflow.
- `FeesPropertyTest` (jqwik, 10,000 tries each): the result equals a `BigDecimal` HALF_UP reference, used only in the test, for all amounts from 0 to 100,000,000 and every `fee_bps`; 0 ≤ fee ≤ amount; the fee never decreases as the amount increases.
