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
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUD3 = "bud3-c0,bud3-c0.001,bud3-c1,bud3-c1000000000"

# (name, JVM properties, expected): "zero" = no failing query; "some" = at least one failing query.
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
    ("defect 6: aborted discovery keeps candidates mid-rebuild", ["-Dfault=6", "-DforceMidCandidateAbort=true", "-Dpolicies=" + BUD3], "some"),
    ("defect 7: backoff answers from the stale set", ["-Dfault=7", "-Dpolicies=" + BUD3], "some"),
    ("defect 8: whole-database index misses the last sequence", ["-Dfault=8", "-Dpolicies=keep-sfx,bud3-c1"], "some"),
    ("defect: dense build stores s-pairs backward", ["-Deucs.build=dense", "-Deucs.fault=3", "-Dpolicies=keep-sfx," + BUD3], "some"),
    ("defect: partitioned build loses a partition", ["-Deucs.build=partitioned", "-Deucs.fault=4", "-Dpolicies=keep-sfx," + BUD3], "some"),
    ("defect: sketch estimate below the true value", ["-Deucs.build=sketch", "-Deucs.fault=5", "-Dpolicies=keep-sfx," + BUD3], "some"),
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
                      ("self-test: campaign runner", [sys.executable, "tools/campaign.py", "--self-test"])):
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
        ok = compared > 0 and nonempty > 0 and ((failing == 0) if expected == "zero" else (failing > 0))
        detail = "compared %d (non-empty %d), failing %d, expected %s (%.0f s)" % (compared, nonempty, failing, expected, secs)
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
