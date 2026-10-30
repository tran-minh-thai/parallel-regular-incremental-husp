#!/usr/bin/env python3
"""The repository's test suite: tool self-tests, then randomised exactness against brute force, then every
deliberate defect, each with the outcome it must produce.

    python3 tools/run_tests.py [--trials 150] [--compile] [--only NAME]

The exactness runs use test.QueryScheduleStress: random tiny databases with multi-item events, each query
of each policy compared with an independent brute-force enumeration. A clean run must report zero failing
queries over a non-zero number of compared ones; a run with an injected defect must report at least one
failing query (a defect that goes unnoticed means the check is blind). Seeds are fixed (1..trials), so the
counts are reproducible. Exit status is non-zero when any outcome differs from the expected one.
Standard library only; runs on Windows and macOS.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUD3 = "bud3-c0,bud3-c0.001,bud3-c1,bud3-c1000000000"

# (name, JVM properties, expected): "zero" = no failing query; "some" = at least one failing query;
# "zero+aborts" = no failing query over a run that aborted at least one discovery in mining.
STRESS = [
    ("clean: all policies", [], "zero"),
    ("clean: dense co-occurrence build", ["-Deucs.build=dense", "-Dpolicies=keep-sfx," + BUD3], "zero"),
    ("clean: partitioned build", ["-Deucs.build=partitioned", "-Dpolicies=keep-sfx," + BUD3], "zero"),
    ("clean: sketch-filtered build", ["-Deucs.build=sketch", "-Dpolicies=keep-sfx," + BUD3], "zero"),
    ("clean: sketch with 16 counters per row", ["-Deucs.build=sketch", "-Deucs.sketchLog=4", "-Dpolicies=keep-sfx," + BUD3], "zero"),
    ("clean: original hash build", ["-Deucs.build=hash", "-Dpolicies=keep-sfx," + BUD3], "zero"),
    ("defect 1: discovery threshold one unit too high", ["-Dfault=1", "-Dpolicies=keep-sfx"], "some"),
    ("defect 2: anchor reset drops the raw increment", ["-Dfault=2", "-Dpolicies=reset-sfx"], "some"),
    ("defect 3: fresh candidates rebuilt from the increment only", ["-Dfault=3", "-Dpolicies=keep-sfx"], "some"),
    ("defect 4: half-built candidates left in the tracked set (measured harmless)", ["-Dfault=4", "-Dpolicies=" + BUD3], "zero"),
    ("defect 5: aborted discovery skips the fallback re-mine", ["-Dfault=5", "-Dpolicies=" + BUD3], "some"),
    # Defect 6 needs a query after the abort that answers from the tracked set. bud3 re-mines the queries after an
    # abort (window backoff), so there the defect cannot act (measured: 0 failing over 2708 queries). bud and bud2
    # share the abort path without backoff; with a budget so large that only the test hook aborts, the outcome does
    # not depend on timing (measured three times: 3, 3 and 2 failing over 1354 queries; clean twin: 0). The count stays
    # small because the hook hits one candidate per abort, and which one varies with thread scheduling: a weak check.
    ("defect 6: aborted discovery keeps candidates mid-rebuild",
     ["-Dfault=6", "-DforceMidCandidateAbort=true", "-Dpolicies=bud2-c1000000000,bud-c1000000000"], "some"),
    ("clean: hook-only aborts, bud and bud2", ["-DforceMidCandidateAbort=true", "-Dpolicies=bud2-c1000000000,bud-c1000000000"], "zero"),
    ("defect 7: backoff answers from the stale set", ["-Dfault=7", "-Dpolicies=" + BUD3], "some"),
    ("defect 8: whole-database index misses the last sequence", ["-Dfault=8", "-Dpolicies=keep-sfx,bud3-c1"], "some"),
    ("defect: dense build stores s-pairs backward", ["-Deucs.build=dense", "-Deucs.fault=3", "-Dpolicies=keep-sfx," + BUD3], "some"),
    ("defect: partitioned build loses a partition", ["-Deucs.build=partitioned", "-Deucs.fault=4", "-Dpolicies=keep-sfx," + BUD3], "some"),
    ("defect: sketch estimate below the true value", ["-Deucs.build=sketch", "-Deucs.fault=5", "-Dpolicies=keep-sfx," + BUD3], "some"),
    ("clean: seed fused into the first query (bud3fs)", ["-Dpolicies=keep-sfx," + BUD3.replace("bud3", "bud3fs")], "zero"),
    ("defect 9: fused seed loses the batches before the first query", ["-Dfault=9", "-Dpolicies=" + BUD3.replace("bud3", "bud3fs")], "some"),
    ("clean: reserve gate (bud3rs), premium 0.1, 0 and 1e9",
     ["-Dpolicies=keep-sfx," + BUD3.replace("bud3", "bud3rs") + ",bud3rs-e0-c1,bud3rs-e1000000000-c1"], "zero"),
    # c = 0.001 aborts nearly every attempt, so the reserve goes negative and refuses the later ones.
    ("defect 10: a refused attempt answers from the stale set", ["-Dfault=10", "-Dpolicies=bud3rs-c0.001,bud3rs-c0"], "some"),
    ("clean: heap guard judged on the live heap (bud3live)", ["-Dpolicies=keep-sfx," + BUD3.replace("bud3", "bud3live")], "zero"),
    # A guard of a few bytes is breached at every attempt: bud3 aborts on the raw reading, bud3live collects and then aborts.
    ("clean: heap guard breached at every attempt, bud3 and bud3live",
     ["-DheapGuard=0.000000001", "-Dpolicies=keep-sfx,bud3-c1000000000,bud3live-c1000000000"], "zero+aborts"),
]

# (name, JVM properties, expected) for the reserve check: test.QueryScheduleProbe on SIGN with bud3rs (c = 0.001 and no
# premium: attempts abort and, once the reserve is spent, are refused; c = 1e9: every attempt completes), then
# tools/check_reserve.py on its log. With the default premium the reserve refills faster than the tiny budget drains
# (measured: 8 aborts, 0 refusals), so the refusal path would go unchecked. "pass" = probe exact and the
# check clean over a non-zero number of queries with at least one abort and one refusal; "fail" = the check reports.
RESERVE = [
    ("reserve: probe on SIGN, then the reserve check", [], "pass"),
    ("defect 11: an aborted attempt is not charged to the reserve", ["-Dfault=11"], "fail"),
]

# (name, JVM properties, expected) for test.HeapValveCheck: "pass" = all four cases tested and correct; "fail" = a case fails.
HEAP_VALVE = [
    ("heap valve: raw and live modes on garbage and on live data", [], "pass"),
    ("defect: live heap valve skips its collection", ["-Dheapvalve.fault=1"], "fail"),
    ("defect: live heap valve never re-reads after collecting", ["-Dheapvalve.fault=2"], "fail"),
]


def run(cmd, timeout=3600):
    t0 = time.monotonic()
    p = subprocess.run(cmd, cwd=str(ROOT), capture_output=True, text=True, timeout=timeout)
    return p, time.monotonic() - t0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=150)
    ap.add_argument("--compile", action="store_true", help="compile src/main/java into out/ first")
    ap.add_argument("--only", help="run only the tests whose name contains this text")
    args = ap.parse_args(argv)
    results = []

    if args.compile:
        srcs = [str(p) for p in (ROOT / "src" / "main" / "java").rglob("*.java")]
        p, _ = run(["javac", "--release", "11", "-d", "out"] + srcs)
        results.append(("compile", p.returncode == 0, (p.stderr or "ok").strip().splitlines()[-1] if p.stderr else "ok"))
        if p.returncode != 0:
            print(p.stderr)
            return report(results)

    for name, cmd in (("self-test: machine_info", [sys.executable, "tools/machine_info.py", "--self-test"]),
                      ("self-test: campaign runner", [sys.executable, "tools/campaign.py", "--self-test"]),
                      ("self-test: heap guard summary", [sys.executable, "tools/summarize_heap_guard.py", "--self-test"]),
                      ("self-test: reserve check", [sys.executable, "tools/check_reserve.py", "--self-test"])):
        if args.only and args.only not in name:
            continue
        p, secs = run(cmd)
        last = (p.stdout.strip().splitlines() or ["(no output)"])[-1]
        results.append((name, p.returncode == 0, "%s (%.0f s)" % (last, secs)))
        print("%-70s %s" % (name, "ok" if p.returncode == 0 else "FAILED"), flush=True)

    for name, props, expected in STRESS:
        if args.only and args.only not in name:
            continue
        cmd = ["java", "-Xmx4g"] + props + ["-cp", "out", "test.QueryScheduleStress", str(args.trials)]
        p, secs = run(cmd)
        m = re.search(r"compared-queries=(\d+) with-non-empty-oracle=(\d+) failing=(\d+)", p.stdout)
        if not m:
            results.append((name, False, "no summary line; exit %d; %s" % (p.returncode, (p.stderr or p.stdout)[-300:])))
            print("%-70s FAILED (no summary)" % name, flush=True)
            continue
        compared, nonempty, failing = map(int, m.groups())
        ma = re.search(r"aborts-in-mining=(\d+)", p.stdout)
        aborts = int(ma.group(1)) if ma else -1
        ok = compared > 0 and nonempty > 0 and ((failing > 0) if expected == "some" else (failing == 0))
        if expected == "zero+aborts":
            ok = ok and aborts > 0
        detail = "compared %d (non-empty %d), failing %d, aborts in mining %d, expected %s (%.0f s)" % (
            compared, nonempty, failing, aborts, expected, secs)
        results.append((name, ok, detail))
        print("%-70s %s  %s" % (name, "ok" if ok else "FAILED", detail), flush=True)

    for name, props, expected in RESERVE:
        if args.only and args.only not in name:
            continue
        with tempfile.TemporaryDirectory() as tmp:
            p, secs = run(["java", "-Xmx2g"] + props + ["-Dthreads=2", "-Dout=" + tmp, "-cp", "out", "test.QueryScheduleProbe",
                                                       "datasets/SIGN_seq.txt", "datasets/SIGN_eui.txt", "0.005", "21", "16",
                                                       "every", "bud3rs-c0.001-e0,bud3rs-c1000000000"])
            runs = [d for d in Path(tmp).iterdir() if d.is_dir()]
            m = re.search(r"compared queries=(\d+) failing=(\d+)", p.stdout)
            if p.returncode != 0 or not m or len(runs) != 1:
                results.append((name, False, "probe exit %d, %d run directories; %s" % (p.returncode, len(runs), (p.stderr or p.stdout)[-300:])))
                print("%-70s FAILED (probe)" % name, flush=True)
                continue
            c = subprocess.run([sys.executable, "tools/check_reserve.py", str(runs[0])], cwd=str(ROOT), capture_output=True, text=True)
            log = (runs[0] / "exactness.csv").read_text(encoding="utf-8")
        mc = re.search(r"reserve check: (\d+) queries checked, (\d+) problems", c.stdout)
        checked, problems = (int(mc.group(1)), int(mc.group(2))) if mc else (0, -1)
        aborts, refusals = log.count(",aborted,"), log.count(",gated,")
        if expected == "pass":
            ok = c.returncode == 0 and checked > 0 and problems == 0 and aborts > 0 and refusals > 0
        else:
            ok = c.returncode != 0 and problems > 0
        detail = "probe compared %s failing %s; reserve check %d queries, %d problems; %d aborts, %d refusals, expected %s (%.0f s)" % (
            m.group(1), m.group(2), checked, problems, aborts, refusals, expected, secs)
        results.append((name, ok, detail))
        print("%-70s %s  %s" % (name, "ok" if ok else "FAILED", detail), flush=True)

    for name, props, expected in HEAP_VALVE:
        if args.only and args.only not in name:
            continue
        p, secs = run(["java", "-Xmx2g"] + props + ["-cp", "out", "test.HeapValveCheck"])
        m = re.search(r"heap valve cases tested=(\d+) failing=(\d+)", p.stdout)
        if not m:
            results.append((name, False, "no summary line; exit %d; %s" % (p.returncode, (p.stderr or p.stdout)[-300:])))
            print("%-70s FAILED (no summary)" % name, flush=True)
            continue
        tested, failing = map(int, m.groups())
        ok = tested == 4 and ((failing == 0 and p.returncode == 0) if expected == "pass" else failing > 0)
        detail = "tested %d of 4, failing %d, expected %s (%.0f s)" % (tested, failing, expected, secs)
        results.append((name, ok, detail))
        print("%-70s %s  %s" % (name, "ok" if ok else "FAILED", detail), flush=True)
    return report(results)


def report(results):
    bad = [r for r in results if not r[1]]
    print("\n%d/%d tests as expected" % (len(results) - len(bad), len(results)))
    for name, _, detail in bad:
        print("  FAILED: %s: %s" % (name, detail))
    return 1 if bad or not results else 0


if __name__ == "__main__":
    sys.exit(main())
