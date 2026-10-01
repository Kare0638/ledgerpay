#!/usr/bin/env python3
"""Turns a run-jfr.sh output directory into report.md (design §14.1, #34).

GC pauses, contended monitors, parks that are real waits (not idle pool threads), virtual-thread
pinning, hot methods and allocation, next to the k6 results of the same run.
"""
import collections
import json
import pathlib
import re
import subprocess
import sys

# A thread parked in one of these is waiting for work, not for a resource.
IDLE = ("java.util.concurrent.ThreadPoolExecutor.getTask",
        "java.util.concurrent.ForkJoinPool.awaitWork",
        "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take",
        "org.apache.tomcat.util.threads.TaskQueue",
        "jdk.internal.misc.InnocuousThread",
        "java.lang.ref.ReferenceQueue",
        "jdk.jfr.internal",
        "sun.nio.ch.SelectorImpl",
        "jdk.internal.net.http.HttpClientImpl$SelectorManager")
# Frames that say nothing about who is waiting.
PLUMBING = re.compile(r"^(java\.util\.concurrent\.|jdk\.internal\.|java\.lang\.(Thread|Object|VirtualThread)\.)")


class Event:
    """One line of JfrExtract output."""

    def __init__(self, line):
        fields = line.rstrip("\n").split("\t")
        self.type, self.ms, self.subject, self.thread, self.extra = (
            fields[0], float(fields[1]), fields[2], fields[3], fields[4])
        self.frames = fields[5:]


def caller(names):
    """The first frame that is not lock or thread plumbing: who is waiting."""
    return next((n for n in names if not PLUMBING.match(n)), names[0] if names else "?")


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p / 100 * (len(ordered) - 1))))]


def fmt(value, pattern="{:.1f}"):
    return "–" if value is None else pattern.format(value)


def load(recording):
    """Streams the extractor's output, keeping only what the report needs: under heavy load a
    recording holds millions of parks, far too many to hold as objects."""
    extractor = pathlib.Path(__file__).with_name("JfrExtract.java")
    events = collections.defaultdict(list)
    idle = 0
    with subprocess.Popen(["java", str(extractor), str(recording)], stdout=subprocess.PIPE,
                          text=True, bufsize=1 << 20) as extract:
        for line in extract.stdout:
            event = Event(line)
            if event.type == "jdk.ThreadPark" and any(n.startswith(IDLE) for n in event.frames):
                idle += 1
                continue
            if event.type in ("jdk.ThreadPark", "jdk.JavaMonitorEnter"):
                event.frames = [caller(event.frames)]
            elif event.type == "jdk.ExecutionSample":
                event.frames = event.frames[:1]
            events[event.type].append(event)
    if extract.returncode:
        raise SystemExit(f"JfrExtract failed with exit code {extract.returncode}")
    events["idle_parks"] = idle
    summary = subprocess.run(["jfr", "summary", str(recording)], check=True, capture_output=True,
                             text=True).stdout
    seconds = float(re.search(r"Duration: (\d+) s", summary).group(1))
    return events, seconds


def gc_section(events, seconds):
    collections_ = events["jdk.GarbageCollection"]
    pauses = [float(e.extra.split("/")[0]) for e in collections_]
    longest = [float(e.extra.split("/")[1]) for e in collections_]
    by_name = collections.Counter(e.subject for e in collections_)
    total = sum(pauses)
    lines = ["## GC pauses", "",
             f"{len(collections_)} collections ({', '.join(f'{n} × {c}' for c, n in by_name.most_common())}) "
             f"in a {seconds:.0f} s recording.", "",
             "| Pause per collection | ms |", "|---|---:|",
             f"| p50 | {fmt(percentile(pauses, 50), '{:.2f}')} |",
             f"| p99 | {fmt(percentile(pauses, 99), '{:.2f}')} |",
             f"| max (longest single pause) | {fmt(max(longest, default=None), '{:.2f}')} |",
             f"| total | {total:.1f} ({total / (seconds * 1000) * 100:.2f} % of the recording) |", ""]
    return lines


def monitor_section(events):
    grouped = collections.defaultdict(list)
    for event in events["jdk.JavaMonitorEnter"]:
        grouped[(event.subject, caller(event.frames))].append(event.ms)
    lines = ["## Contended monitors (`jdk.JavaMonitorEnter`, ≥ 10 ms)", ""]
    if not grouped:
        return lines + ["None recorded.", ""]
    lines += ["| Monitor class | Waiting in | Count | Total ms | Max ms |", "|---|---|---:|---:|---:|"]
    for (monitor, site), waits in sorted(grouped.items(), key=lambda kv: -sum(kv[1]))[:10]:
        lines.append(f"| `{monitor}` | `{site}` | {len(waits)} | {sum(waits):.0f} | {max(waits):.0f} |")
    return lines + [""]


def park_section(events):
    grouped = collections.defaultdict(list)
    idle = events["idle_parks"]
    for event in events["jdk.ThreadPark"]:
        thread = re.sub(r"\d+", "N", event.thread)
        grouped[(event.subject, caller(event.frames), thread)].append(event.ms)
    lines = ["## Parked threads waiting for a resource (`jdk.ThreadPark`, ≥ 10 ms)", "",
             f"{idle} parks of idle pool, scheduler, selector and JFR threads are left out.", ""]
    if not grouped:
        return lines + ["None recorded.", ""]
    lines += ["| Parked on | Waiting in | Threads | Count | Total ms | Max ms |", "|---|---|---|---:|---:|---:|"]
    for (parked, site, thread), waits in sorted(grouped.items(), key=lambda kv: -sum(kv[1]))[:10]:
        lines.append(f"| `{parked}` | `{site}` | `{thread}` | {len(waits)} | {sum(waits):.0f} | {max(waits):.0f} |")
    return lines + [""]


def hot_section(events):
    samples = collections.Counter()
    for event in events["jdk.ExecutionSample"]:
        if event.frames:
            samples[event.frames[0]] += 1
    allocation = collections.Counter()
    for event in events["jdk.ObjectAllocationSample"]:
        allocation[event.subject] += int(event.extra)
    total_samples = sum(samples.values()) or 1
    total_bytes = sum(allocation.values()) or 1
    lines = ["## Where CPU and allocation go", "",
             f"Top methods by execution samples ({sum(samples.values())} samples):", "",
             "| Method | Share |", "|---|---:|"]
    lines += [f"| `{m}` | {c / total_samples * 100:.1f} % |" for m, c in samples.most_common(8)]
    lines += ["", "Top allocated classes (sampled weight):", "", "| Class | Share |", "|---|---:|"]
    lines += [f"| `{c}` | {w / total_bytes * 100:.1f} % |" for c, w in allocation.most_common(8)]
    return lines + [""]


def k6_section(directory):
    metrics = json.loads((directory / "k6-summary.json").read_text())["metrics"]

    def trend(name, stat):
        values = metrics.get(name, {})
        return values.get(stat)

    def count(name, field="count"):
        return metrics.get(name, {}).get(field)

    states = (directory / "payment-states.txt").read_text().split()
    captured = next((s.split("|")[1] for s in states if s.startswith("CAPTURED|")), "0")
    lines = ["## Load (k6)", "",
             "| Metric | Value |", "|---|---:|",
             f"| Completed payments (create → captured) | {captured} |",
             f"| Iterations started per second | {fmt(count('iterations', 'rate'), '{:.1f}')} |",
             f"| Incomplete payments (timed out or rejected) | {fmt(count('incomplete_payments') or 0, '{:.0f}')} |",
             f"| Dropped iterations (no free VU) | {fmt(count('dropped_iterations') or 0, '{:.0f}')} |",
             f"| HTTP request p50 / p99 (ms) | {fmt(trend('http_req_duration', 'med'))} / {fmt(trend('http_req_duration', 'p(99)'))} |",
             f"| Create → authorised p50 / p99 (ms) | {fmt(trend('time_to_authorised', 'med'), '{:.0f}')} / {fmt(trend('time_to_authorised', 'p(99)'), '{:.0f}')} |",
             f"| Capture → captured p50 / p99 (ms) | {fmt(trend('time_to_captured', 'med'), '{:.0f}')} / {fmt(trend('time_to_captured', 'p(99)'), '{:.0f}')} |",
             f"| HTTP failures | {fmt((trend('http_req_failed', 'value') or 0) * 100, '{:.2f}')} % |", "",
             "Payment states at the end (in-flight payments are cut off when the load stops): "
             + ", ".join(f"`{s.replace('|', '`: ')}" for s in states), ""]
    return lines


def main(directory):
    directory = pathlib.Path(directory)
    events, seconds = load(directory / "payment-service.jfr")
    pinned = len(events["jdk.VirtualThreadPinned"])
    report = [(directory / "environment.md").read_text().strip(), ""]
    report += k6_section(directory)
    report += gc_section(events, seconds)
    report += monitor_section(events)
    report += park_section(events)
    report += ["## Virtual-thread pinning", "", f"{pinned} `jdk.VirtualThreadPinned` events.", ""]
    report += hot_section(events)
    (directory / "report.md").write_text("\n".join(report) + "\n")
    print((directory / "report.md").read_text())


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "load-tests/target")
