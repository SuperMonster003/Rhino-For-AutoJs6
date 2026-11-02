"""Compare ClassBenchmark entries with alternating JMH forks, one fork of each entry per round.

usage: py -3.12 -I .python/jmh_alternate.py <out-dir> <rounds> <Compiler|Interpreter> <label=jar@benchmark>...
       py -3.12 -I .python/jmh_alternate.py --summary <out-dir> [<base-label>]

The jar is a :benchmarks:jmhJar output, so a saved build can be compared with the current one. The
order of the entries is reversed every other round. The first entry is the base of the comparison.
JMH reports the error inside one fork; forks of the same benchmark differ more than that (ROADMAP
appendix C Q51), so a single run of `:benchmarks:jmh` cannot decide a difference of 20%.

Each round writes r<round>-<label>.json and .txt to <out-dir>, and the CPU load before and after each
fork to load.txt. The summary prints, for every entry, the median, the median error, the difference
to the base, the median of the per round ratios, the allocation per operation of the loop and the
scores in round order.
"""

import json
import os
import re
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOAD = "(Get-CimInstance Win32_Processor).LoadPercentage"
OPERATIONS = 1000


def java():
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else "java"


def cpu_load():
    if os.name != "nt":
        return "?"
    run = subprocess.run(
        ["powershell", "-NoProfile", "-Command", LOAD], capture_output=True, text=True
    )
    return run.stdout.strip() or "?"


def fork(out, round_no, mode, label, jar, benchmark):
    name = out / f"r{round_no}-{label}"
    command = [java(), "-jar", jar, rf"ClassBenchmark\.{benchmark}$", "-p", f"evalMethod={mode}"]
    command += ["-bm", "avgt", "-f", "1", "-wi", "4", "-w", "5s", "-i", "5", "-r", "2s"]
    command += ["-jvmArgs", "-XX:-BackgroundCompilation", "-prof", "gc", "-foe", "true"]
    command += ["-rf", "json", "-rff", str(name) + ".json"]
    before = cpu_load()
    with open(str(name) + ".txt", "w", encoding="utf-8") as log:
        code = subprocess.run(command, cwd=ROOT / "benchmarks", stdout=log, stderr=log).returncode
    with open(out / "load.txt", "a", encoding="utf-8") as log:
        log.write(f"r{round_no} {label} load {before} {cpu_load()}\n")
    if code != 0:
        sys.exit(f"{name}: JMH exit code {code}")


def run(out, rounds, mode, entries):
    out = out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    parsed = []
    for entry in entries:
        match = re.fullmatch(r"([^=]+)=(.+)@(\w+)", entry)
        if match is None:
            sys.exit(f"not label=jar@benchmark: {entry}")
        parsed.append((match[1], str(Path(match[2]).resolve()), match[3]))
    for round_no in range(1, rounds + 1):
        for label, jar, benchmark in parsed if round_no % 2 else reversed(parsed):
            fork(out, round_no, mode, label, jar, benchmark)
    summary(out, parsed[0][0])


def scores(out):
    rows = {}
    for file in out.glob("r*-*.json"):
        match = re.fullmatch(r"r(\d+)-(.+)\.json", file.name)
        results = json.loads(file.read_text(encoding="utf-8"))
        if not results:
            continue
        primary = results[0]["primaryMetric"]
        allocation = 0
        for key, metric in results[0].get("secondaryMetrics", {}).items():
            if key.endswith("gc.alloc.rate.norm"):
                allocation = metric["score"]
        row = rows.setdefault(match[2], {})
        row[int(match[1])] = (primary["score"], primary["scoreError"], allocation)
    return rows


def summary(out, base):
    rows = scores(out)
    base_rows = rows[base]
    base_median = statistics.median(value[0] for value in base_rows.values())
    print(f"{'entry':24s} {'median':>8s} {'error':>6s} {'diff':>7s} {'ratio':>7s} {'B/op':>7s}  rounds")
    for label, row in rows.items():
        median = statistics.median(value[0] for value in row.values())
        error = statistics.median(value[1] for value in row.values())
        ratios = [row[n][0] / base_rows[n][0] for n in row if n in base_rows]
        allocation = statistics.median(value[2] for value in row.values()) / OPERATIONS
        values = " ".join(f"{row[n][0]:.1f}" for n in sorted(row))
        print(
            f"{label:24s} {median:8.2f} {error:6.1f} {(median / base_median - 1) * 100:+6.1f}%"
            f" {(statistics.median(ratios) - 1) * 100:+6.1f}% {allocation:7.1f}  {values}"
        )
    load = out / "load.txt"
    if load.exists():
        found = re.findall(r" load (\d+) (\d+)", load.read_text(encoding="utf-8"))
        loads = [int(value) for pair in found for value in pair]
        if loads:
            print(f"load: min {min(loads)}, median {statistics.median(loads)}, max {max(loads)}")


def main():
    args = sys.argv[1:]
    if args[:1] == ["--summary"] and len(args) >= 2:
        out = Path(args[1])
        summary(out, args[2] if len(args) > 2 else sorted(scores(out))[0])
    elif len(args) >= 4:
        run(Path(args[0]), int(args[1]), args[2], args[3:])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
