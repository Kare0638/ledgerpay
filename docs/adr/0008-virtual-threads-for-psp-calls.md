# ADR 0008 — Virtual threads for PSP calls

**Status:** accepted · **Date:** 2026-10-01 · **Issue:** [#33](https://github.com/Kare0638/ledgerpay/issues/33)

## Context

`PspOperationWorker` claims a batch of due operations and calls the PSP for each one outside any transaction (design §9.1). The calls are I/O: a request, a wait for the PSP, and a short update to record the acceptance. Which executor runs the calls decides how much PSP latency one worker can overlap, and how many threads it costs to do so.

The candidates were a virtual thread per call (`Executors.newVirtualThreadPerTaskExecutor()`) and fixed pools of platform threads (10, 50 and 200). A database connection is needed only for the short update, so the Hikari pool size was varied too, to test the guess that the pool, not the thread count, would cap throughput.

All numbers come from the JMH benchmark in `benchmarks/` (design §14.1), run by the `Benchmarks` workflow on a GitHub-hosted runner: 4 CPUs (AMD EPYC), 15 GB RAM, JDK 21.0.12, commit `621f2c2`. One operation is one `runOnce()` over a freshly seeded batch, through the real worker, PSP client and SQL, against a stub PSP in its own JVM. On this shared runner the same parameters varied by up to about 25 % between passes, so comparisons are made within one pass.

## Decision

**A virtual thread per PSP call**, with the PSP client's `HttpClient` also on virtual threads.

Mean time per batch, Hikari pool 10:

| Batch × PSP delay | virtual | fixed-10 | fixed-50 | fixed-200 |
|---|---:|---:|---:|---:|
| 200 × 200 ms | **346 ms** | 4,157 ms | 906 ms | 337 ms |
| 200 × 20 ms | **142 ms** | 518 ms | 236 ms | 143 ms |
| 20 × 200 ms | **217 ms** | 419 ms | 215 ms | 216 ms |
| peak platform threads | **30** | 35 | 75 | 225 |

Virtual threads match the largest pool on latency, and are 12 times faster than a pool of 10 when the PSP is slow and the batch is large, without the platform threads that the pool of 200 costs. There was no pinning (`jdk.VirtualThreadPinned`: 0 in every run). The difference between executors is negligible only when the PSP answers at once.

**The connection-pool guess was wrong.** With 200 virtual threads and a 20 ms PSP, Hikari pools of 2, 5, 10 and 50 gave 187, 162, 184 and 171 ms per batch, which is within run-to-run noise. The update that needs a connection is short enough that even two connections keep up. The pool stays at Hikari's default.

**The HTTP client needed an executor of its own.** In the first JFR recording, the virtual-thread executor had *more* platform threads at its peak than the pool of 10. 2,400 threads named `SimpleAsyncTaskExecutor-N` were started in 4 seconds, one per PSP call. Spring's `JdkClientHttpRequestFactory` writes request bodies on the `HttpClient`'s executor and, when the client has none, falls back to a `SimpleAsyncTaskExecutor`, which starts a new platform thread for every request. `PspClient` now gives its `HttpClient` a virtual-thread executor. In the same pass, batch 200 × 20 ms:

| HTTP executor | virtual worker | fixed-50 worker | peak platform threads (virtual / fixed-50) |
|---|---:|---:|---:|
| Spring's fallback | 224 ms | 237 ms | 146 / 129 |
| virtual threads | **148 ms** | **204 ms** | **30 / 75** |

`PspOperationWorkerIntegrationTest.pspCallsDoNotStartAPlatformThreadEach` keeps it that way: 6 platform threads started for a batch of 5 with the fallback, 0 with the fix.

**On one or two CPUs the HTTP client starts a thread per request anyway (found 2026-10-05).** CI began to fail `concurrentWorkersNeverProcessTheSameOperation` intermittently, and limiting the tests to two CPUs (`taskset -c 0,1`) reproduced it every time, on `main` as well. Thread dumps and JFR showed two separate problems:

- *In the test stub (test-only).* `StubPsp` served requests on virtual threads. On JDK 21, the built-in `HttpServer` reads a request body inside synchronized code, so a handler waiting for the body pins its carrier. Two such handlers pinned both carriers of a two-CPU machine. The HTTP client's virtual threads could then not run to send those bodies, and the calls ran into the read timeout together. `jdk.tracePinnedThreads` stayed silent; the dumps showed `parkOnCarrierThread` under `StubPsp` and a queue of virtual threads that had never run. The stub now uses 64 platform threads, all started before any test measures thread counts.
- *In production.* JFR `jdk.ThreadStart` showed a platform thread per call, started by `CompletableFuture$ThreadPerTaskExecutor` from `Http1Response$HeadersReader`. The JDK HTTP client completes some stages with CompletableFuture's default executor, not with the executor it is given. That default is the common pool only when the pool's parallelism is at least 2. On one or two CPUs it is 1, and CompletableFuture starts a new thread for every task instead. ECS Fargate tasks with 0.5–2 vCPUs (#24) are exactly that case.

The fix is `-Djava.util.concurrent.ForkJoinPool.common.parallelism=2`: in the Dockerfile, in the surefire and failsafe `argLine`, and for the mock-psp the acceptance tests start. `PspClient` logs a warning at startup if the parallelism is still below 2. With both fixes, `PspOperationWorkerIntegrationTest` passes on two CPUs; before, 2–3 of its 17 tests failed each run.

## Rejected alternatives

| Alternative | Why not |
|---|---|
| A fixed pool of 200 | As fast here, but 225 platform threads where 30 do, and its size is one more number to tune against the batch size and PSP latency. |
| A fixed pool of 10 or 50 | Up to 12 times slower whenever the batch is larger than the pool and the PSP is slow, which is exactly when the worker most needs to overlap calls. |
| Asynchronous `HttpClient` calls with `CompletableFuture` | Similar concurrency, but the worker, retries and inquiry-first logic would become callback chains, where with virtual threads they stay plain blocking code. |
| A larger Hikari pool | No measurable effect; see above. |

## Trade-offs

- On JDK 21 a virtual thread that blocks inside `synchronized` pins its carrier. None of today's call path does, and the benchmark's JFR pass counts `jdk.VirtualThreadPinned` so that a library upgrade that starts pinning shows up.
- The numbers are from a shared 4-CPU runner, not a dedicated host. The ratios between executors are consistent across passes; the absolute times are not.

## Tests and evidence

- `benchmarks/` (`PspDispatchBenchmark`, `run.sh`, `analyze.py`) and the `Benchmarks` workflow, which publishes the report as a job summary and artifact.
- `PspOperationWorkerIntegrationTest.pspCallsDoNotStartAPlatformThreadEach`.
