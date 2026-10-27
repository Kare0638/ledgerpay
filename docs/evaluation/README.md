# Live reconciliation-assistant evaluation (#26 / #64)

This experiment compares the production `AssistantAgent` on the same five held-out breaks with
no precedents and with three precedents retrieved by the production `AssistantStore` from
PostgreSQL/pgvector using real Gemini embeddings. Gemini generation, function calling, tools,
validation and retrieval are real; there is no scripted model or deterministic embedding.

The fixtures reproduce the five break types in `demo/run.sh`: lost capture webhook, duplicate
report row, missing report row, 5000 versus 5100 minor units, and an unknown transaction. They
are deliberately small SQL-seeded payment histories, not a replay of the full mock-PSP demo.
Successful captures have balanced journals through `Ledger.post`; pending captures have none.
Each CSV is imported and reconciled through the real services, and every run must pass its ledger
integrity check. The worker, inbox processor and outbox relay are disabled by test configuration.

## Protocol

1. Create five separate historical breaks, one per type, on distinct business days with new IDs
   and doubled amounts. Generate their analyses without retrieval, embed their signatures with
   `gemini-embedding-001` (768 dimensions), and persist them. Require completed, correctly labelled
   analyses before admitting them to the curated corpus. Confirm any lost-webhook inquiry in the
   isolated test database; no worker executes it. These five cases are **not** evaluation scores.
   Alternatively, reuse a saved, correctly labelled corpus with `-Dledgerpay.eval.corpus=...`.
   The harness restores the historical target IDs, merchant, amount and business date, creates
   new control operations and reconciliation runs, embeds the signatures again, and revalidates
   the saved analysis's schema and citations against the restored facts. It keeps the original
   corpus model and source analysis IDs in the report. No generation calls are made for reused
   corpus entries; their `elapsedMs` measures deserialization, not inference. A model that fails
   corpus admission is recorded separately, never silently relabelled.
2. Freeze that corpus. Create another five breaks with fresh IDs and dates. For each one, use the
   same facts, system prompt, function declarations, model and generation defaults in both arms.
   The RAG arm retrieves three precedents; the baseline gets none. Require the first retrieved
   precedent's cause to match the scenario, so a missing embedding or broken retrieval cannot
   silently become a baseline run. Alternate baseline/RAG execution order across scenarios.
3. Keep evaluation outputs out of the corpus. Save input facts, expected labels, retrieved
   precedents, complete `AgentResult` (including rejected tool calls), latency and transport errors
   after each analysis. Each model conversation starts fresh. Generation uses API defaults with
   no fixed seed or temperature; model names are aliases, not pinned model versions.

Automatic checks score the cause against a label chosen from the injected fault, and whether an
inquiry was proposed (only the lost-webhook case should propose one). `COMPLETED` means the
production schema and ID-grounding validator accepted the answer; it does **not** prove that
all prose is factually correct. Read explanations, evidence and next steps manually for unsupported
claims, overconfidence and unsafe advice. Report those failures separately, even when the label
is correct. No evaluation proposal is confirmed.

## Reproduce

Requires JDK 21, Docker and the private key file described in [SECURITY.md](../../SECURITY.md#secrets).
This is an opt-in experiment: normal CI skips it, even if a Gemini key happens to be set.

```bash
LEDGERPAY_LIVE_EVAL=true scripts/with-gemini.sh ./mvnw -pl payment-service -am test \
  -Dtest=AssistantLiveEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Output: `payment-service/target/assistant-evaluation.json`. Override with
`-Dledgerpay.eval.output=/absolute/path/to/results.json`. The file is checkpointed as the run
progresses; an interrupted run is not a complete comparison. It contains synthetic identifiers
and model outputs, never request headers or the key. Review it before publishing.

A fresh pass needs 10 embedding calls (five corpus, five queries) and at least 15 generation calls
(five corpus, ten scored analyses); reusing a saved corpus needs the same 10 embeddings but only
the 10 scored analyses' generation calls. Extra tool turns and retries can exhaust the configured
project's quota. Do not substitute stubs or omit failed cases to make a result look complete.
Latency measures generation/tool turns, including retries, but excludes embeddings, retrieval
and fixture setup. Token totals are the production agent's accumulated `totalTokenCount` over
turns, including repeated history; they are not separately measured billable input/output tokens.

One run of five paired cases is a smoke evaluation with a curated same-type precedent available
for every query. It cannot establish statistical improvement, robustness to bad precedents,
production accuracy or an end-to-end latency advantage. The categories and tool facts already
strongly suggest each cause, so retrieval may add little. Future evaluations should include
ambiguous breaks, conflicting precedents, repeated runs and blinded human review.

## Recorded runs, 27 October 2026

The production-code baseline was commit `093476180d026ac9e026cd87bb717105315abe83`; the opt-in
harness is included alongside these artifacts. Models below are separate experiments, not pooled
accuracy samples.

- [`gemini-3.5-flash`](2026-10-27-gemini-3.5-flash.json): generated the five admitted precedents;
  completed both lost-webhook analyses; the other eight analyses hit daily quota. The first
  pair used 3/2 turns, 11,225/7,494 tokens and 39,180/9,732 ms without/with RAG. Latencies are
  observations from one sequential pair, not a speed benchmark.
- [`gemini-2.5-flash-lite` availability probe](2026-10-27-gemini-2.5-flash-lite-unavailable.json):
  the first corpus generation returned 404; no evaluation answers. Listing a model did not mean
  it was available to this key.
- [`gemini-3.5-flash-lite` fresh-corpus attempt](2026-10-27-gemini-3.5-flash-lite-corpus-failure.json):
  the unknown transaction received `MISSING_FROM_REPORT` with HIGH confidence, although the prose
  correctly said it was present in the report and absent internally. Expected `UNKNOWN_TRANSACTION`.
  Admission stopped before evaluation. This is a genuine label/prose contradiction missed by the
  schema/ID validator, not a scored held-out case.

- [`gemini-3.5-flash-lite` with the reused corpus](2026-10-27-gemini-3.5-flash-lite.json): all
  ten held-out analyses completed. Labels were correct on 4/5 baseline and 5/5 RAG cases; inquiry
  decisions were correct on 5/5 each. Baseline/RAG used 9/8 turns and 19,518/20,319 accumulated tokens.
  The unknown-transaction baseline repeated the HIGH-confidence wrong label; RAG selected the
  expected `UNKNOWN_TRANSACTION`. No rejected tool calls occurred in these ten analyses.
  Generation/tool latency totaled 13,298/12,276 ms across five cases, excluding embeddings and
  retrieval. These single-run timings do not establish a latency improvement.

Manual review of the complete comparison found unit ambiguity in both amount-mismatch answers
(5000/5100 GBP instead of clearly identifying £50/£51). The RAG lost-webhook explanation said a
precedent “was resolved”, although the supplied hint records only a confirmed inquiry, not a
successful inquiry outcome or a subsequent matched run. Its next steps also said to update the
books without naming the ordinary outcome-application path. All ten answers were HIGH confidence,
including the baseline's wrong unknown-transaction label. There were no observed invented IDs or
executed money changes; that does not establish complete factual correctness or calibrated safety.
See the root README for the paired table and examples. This review is by the coding agent against
the saved facts, not an independent or blinded human evaluation.

To repeat a comparison using the already generated corpus (without using its held-out answers):

```bash
GEMINI_MODEL=gemini-3.5-flash-lite LEDGERPAY_LIVE_EVAL=true scripts/with-gemini.sh \
  ./mvnw -pl payment-service -am test -Dtest=AssistantLiveEvaluationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dledgerpay.eval.corpus="$PWD/docs/evaluation/2026-10-27-gemini-3.5-flash.json"
```

Synthetic fixture limits matter: these histories do not simulate PSP HTTP attempts or received
webhooks, so even successful operations have zero attempts and an empty webhook list. Creation
and posting timestamps use the run clock while business dates are historical. This tests analysis
of the break facts, not realistic chronology or discrimination between missing and late outcomes.
The generator's HIGH confidence should not be read as calibrated confidence about root cause.
