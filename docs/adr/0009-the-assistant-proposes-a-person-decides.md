# ADR 0009 — The reconciliation assistant reads facts and proposes; a person decides

**Status:** accepted · **Date:** 2026-10-27 · **Issue:** [#26](https://github.com/Kare0638/ledgerpay/issues/26)

## Context

A reconciliation break (design §11) is explained by facts the system already holds: the report lines, our operation as the run saw it, the operation's attempts and last error, its journals, the webhooks received. Putting them together is routine work for an operator, and a language model can draft it. But a model's output is text that may be wrong, invented or shaped by its input, and this system moves money. The PSP report it reads is itself input from outside.

What the assistant may do had to be settled before how well it does it.

## Decision

**Read-only facts in, one proposed action out, a fixed schema checked before anything is shown.**

- **No access, only facts.** The model is given what three read-only tools return, scoped to one break: the item and its report lines, its operation's timeline, operations by PSP reference. It has no SQL, no ledger, no merchants, no keys. The facts for the break come with the question; the tools answer anything further.
- **One action, and it is a proposal.** `propose_inquiry` records a proposal, at most one per analysis, for this break's own PENDING operation only. A person confirms it, through the same path as an operator's inquiry (design §9.1), or rejects it. An inquiry is safe to repeat and changes the books only through the PSP's own answer (ADR 0004), so even a confirmed bad proposal costs one PSP call.
- **A fixed schema, validated.** The analysis is the arguments of a `submit_analysis` tool: an enum cause and confidence, bounded text, evidence lines. The service checks the enums and lengths and that every UUID or PSP reference cited was returned by a tool. One refusal goes back to the model; a second is stored as `INVALID_OUTPUT` and nothing is displayed.
- **Everything kept.** Analyses are append-only, with the tool calls in order; a proposal is decided once, by a named person.

## Alternatives rejected

- **Let the model trigger inquiries directly.** Inquiries are harmless one by one, but a model looping or misled by a crafted report line could spend the PSP's rate limit and the worker's attention. A person in the loop costs one click.
- **Let the model write SQL against a read replica.** Broadest answers, but no limit on what it reads (merchant data, keys' hashes) and no way to check its citations against what it was shown.
- **Free-text answers.** Easier for the model, impossible to validate or to count in an evaluation.
- **An SDK.** Gemini's REST API is one POST; the JDK client with the header we choose keeps the key's path short and testable against a stub.

## Trade-offs

- Preloading the facts sends some the model may not need, in exchange for one or two calls per analysis instead of four or five: on Gemini's free tier, 20 requests a day per model.
- Checking cited IDs catches invented ones, not wrong reasoning over real ones. That is what the evaluation set (design §16) measures.
- The free tier allows Google to use prompts; only synthetic demo data is sent. A real deployment would need a paid tier or a self-hosted model.

## Tests

`AssistantIntegrationTest` (against a stub Gemini): the lost webhook analysed in one turn from preloaded facts, with the proposal recorded although the model made it after submitting; the key only in the request header, never in the URL, body, logs or errors; a proposal for another operation refused and the model told; an invented ID sent back once, then the corrected analysis stored; two refusals stored as `INVALID_OUTPUT`; a model that never submits given up after the turn limit; confirmation scheduling the inquiry once, rejection scheduling nothing; a daily quota failing at once and a short wait honoured. `AnalysisValidatorTest` and `SecretsTest` cover the schema checks and redaction.
