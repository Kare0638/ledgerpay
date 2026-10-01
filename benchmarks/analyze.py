#!/usr/bin/env python3
"""Turns a run.sh results directory into report.md (design §14.1).

Timing comes from the pass without JFR, so recording overhead never touches the latencies;
threads, heap and pinning come from the JFR pass of the same benchmark parameters.
"""
import json
import pathlib
import re
import subprocess
import sys

EVENTS = "jdk.JavaThreadStatistics,jdk.GCHeapSummary,jdk.VirtualThreadStart,jdk.VirtualThreadEnd,jdk.VirtualThreadPinned"
EXECUTOR_ORDER = {"virtual": 0, "fixed-10": 1, "fixed-50": 2, "fixed-200": 3}


PARAMS = re.compile(r"batchSize-(\d+)-executor-(.+?)-httpExecutor-(.+?)-poolSize-(\d+)-pspDelayMillis-(\d+)$")


def key(params):
    return (params["executor"], int(params["pspDelayMillis"]), int(params["batchSize"]),
            int(params["poolSize"]), params.get("httpExecutor", "virtual"))


def timings(path):
    rows = {}
    for result in json.loads(path.read_text()):
        metric = result["primaryMetric"]
        percentiles = metric["scorePercentiles"]
        rows[key(result["params"])] = {
            "mean": metric["score"],
            "p50": percentiles["50.0"],
            "p99": percentiles["99.0"],
            "samples": sum(len(r) for r in metric.get("rawDataHistogram", [])) or None,
        }
    return rows


def jfr_metrics(recording):
    out = subprocess.run(["jfr", "print", "--json", "--events", EVENTS, str(recording)],
                         check=True, capture_output=True, text=True).stdout
    events = json.loads(out)["recording"]["events"]
    peak_platform = max((e["values"]["peakCount"] for e in events
                         if e["type"] == "jdk.JavaThreadStatistics"), default=None)
    heap_after_gc = max((e["values"]["heapUsed"] for e in events
                         if e["type"] == "jdk.GCHeapSummary" and e["values"]["when"] == "After GC"), default=None)
    # Live virtual threads over time: +1 at each start, -1 at each end, in time order.
    changes = sorted((e["values"]["startTime"], 1 if e["type"] == "jdk.VirtualThreadStart" else -1)
                     for e in events if e["type"] in ("jdk.VirtualThreadStart", "jdk.VirtualThreadEnd"))
    live = peak_virtual = 0
    for _, change in changes:
        live += change
        peak_virtual = max(peak_virtual, live)
    pinned = sum(1 for e in events if e["type"] == "jdk.VirtualThreadPinned")
    return {"peak_platform": peak_platform, "peak_virtual": peak_virtual,
            "heap_mb": None if heap_after_gc is None else heap_after_gc / 1024 / 1024, "pinned": pinned}


def recordings(directory):
    found = {}
    for recording in directory.glob("*/profile.jfr"):
        match = PARAMS.search(recording.parent.name)
        batch, executor, http, pool, delay = match.groups()
        params = {"batchSize": batch, "executor": executor, "httpExecutor": http,
                  "poolSize": pool, "pspDelayMillis": delay}
        found[key(params)] = jfr_metrics(recording)
    return found


def fmt(value, pattern="{:.1f}"):
    return "–" if value is None else pattern.format(value)


def table(times, resources, show_http=False):
    head = "| Executor | " + ("HTTP executor | " if show_http else "") + "PSP delay | Batch | Pool | Mean ms/batch | p50 | p99 | Calls/s | Peak platform threads | Peak live virtual threads | Max heap after GC (MB) | Pinned |"
    lines = [head, "|---|" + ("---|" if show_http else "") + "---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for k in sorted(times, key=lambda k: (k[2], k[1], k[3], EXECUTOR_ORDER.get(k[0], 9), k[4])):
        executor, delay, batch, pool, http = k
        t, r = times[k], resources.get(k, {})
        lines.append(
            f"| {executor} | " + (f"{http} | " if show_http else "") +
            f"{delay} ms | {batch} | {pool} | {fmt(t['mean'])} | {fmt(t['p50'])} | {fmt(t['p99'])} "
            f"| {fmt(batch / t['mean'] * 1000, '{:,.0f}')} | {fmt(r.get('peak_platform'), '{}')} "
            f"| {fmt(r.get('peak_virtual'), '{}')} | {fmt(r.get('heap_mb'))} | {fmt(r.get('pinned'), '{}')} |")
    return "\n".join(lines)


def main(directory):
    directory = pathlib.Path(directory)
    report = [(directory / "environment.md").read_text().strip(), "",
              "## Executor comparison", "",
              table(timings(directory / "timing.json"), recordings(directory / "jfr")), ""]
    if (directory / "pool.json").exists():
        report += ["## Connection-pool sensitivity", "", table(timings(directory / "pool.json"), {}), ""]
    if (directory / "http.json").exists():
        report += ["## HTTP client executor: Spring's default against the fix", "",
                   table(timings(directory / "http.json"), recordings(directory / "jfr-http"), show_http=True), ""]
    (directory / "report.md").write_text("\n".join(report) + "\n")
    print((directory / "report.md").read_text())


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "benchmarks/target/results")
