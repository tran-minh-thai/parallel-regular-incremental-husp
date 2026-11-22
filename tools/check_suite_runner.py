#!/usr/bin/env python3
"""Self-test of the S1-S11 suite runner (test.ExperimentOfficial) in the mode the campaign uses it.

    python3 tools/check_suite_runner.py [--cp out]

Runs the runner on the two tiny example datasets in a throw-away directory and checks what a campaign cell relies on:
the "### RUN DIR ->" line, a DONE marker, one CSV row per measured run with exactly as many fields as the header,
only the scenarios asked for, only thread counts of the declared sweep, no user name in the provenance file. Then three
cases that must be refused: an unknown scenario (exit 2), a declared sweep the machine cannot run (exit 2), and a
dataset the loader reads as empty (exit 3, no DONE marker). Exit status is non-zero when any check fails.
Standard library only; runs on Windows and macOS.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(cp, cwd, props, args):
    cmd = ["java", "-Xmx1g"] + props + ["-cp", str(cp), "test.ExperimentOfficial"] + args
    p = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=1800)
    return p.returncode, p.stdout + p.stderr


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--cp", default=str(ROOT / "out"), help="class path of the compiled runner (default: out/)")
    a = ap.parse_args(argv)
    cp = Path(a.cp).resolve()
    results = []

    def check(ok, msg):
        results.append((bool(ok), msg))

    tmp = Path(tempfile.mkdtemp(prefix="suite_runner_check_"))
    try:
        (tmp / "datasets").mkdir()
        for n in ("example_seq.txt", "example_eui.txt", "example2_seq.txt", "example2_eui.txt"):
            shutil.copy(ROOT / "datasets" / n, tmp / "datasets" / n)
        out = tmp / "results" / "suite"
        sweep = "1,2"

        # 1. a clean run of two scenarios
        code, text = run(cp, tmp, ["-Dsuite.out=results/suite", "-Dsuite.cell=CHECK", "-Dsuite.threads=" + sweep],
                         ["--test", "--scenarios=S1,S8"])
        m = re.search(r"^### RUN DIR -> (\S.*)$", text, re.M)
        check(code == 0, "clean run exits 0 (exit %d)" % code)
        check(m is not None, "clean run prints the run directory line")
        d = tmp / m.group(1).strip() if m else None
        check(d is not None and d.is_dir() and d.parent == out and "CHECK" in d.name,
              "run directory lies under the named root and carries the cell name (%s)" % (d.name if d else None))
        if d is not None and d.is_dir():
            check((d / "DONE").exists(), "clean run writes the DONE marker")
            lines = (d / "results.csv").read_text(encoding="utf-8").splitlines()
            head = lines[0].split(",")
            rows = [l.split(",", -1) for l in lines[1:] if l.strip()]
            check(len(rows) > 0, "clean run writes measured rows (%d)" % len(rows))
            bad = [r for r in rows if len(r) != len(head)]
            check(not bad, "every row has as many fields as the header (%d rows, %d short or long)" % (len(rows), len(bad)))
            ci = {k: i for i, k in enumerate(head)}
            scen = {r[ci["scenario"]] for r in rows if len(r) == len(head)}
            check(scen == {"S1-scalability", "S8-batchscale"}, "only the scenarios asked for (%s)" % sorted(scen))
            th = {r[ci["threads"]] for r in rows if len(r) == len(head)}
            check(th <= set(sweep.split(",")) and th, "only thread counts of the declared sweep (%s)" % sorted(th))
            st = {r[ci["status"]] for r in rows if len(r) == len(head)}
            check(st == {"OK"}, "status column holds statuses (%s)" % sorted(st))
            meta = (d / "meta.properties").read_text(encoding="utf-8")
            check("env.user" not in meta, "no user name in the provenance file")
            check("config.threadSweep=[1, 2]" in meta, "provenance records the declared sweep")

        # 2. refusals
        code, _ = run(cp, tmp, ["-Dsuite.out=results/suite"], ["--test", "--scenarios=S1,S3"])
        check(code == 2, "unknown scenario is refused with exit 2 (exit %d)" % code)
        many = str((os.cpu_count() or 1) + 1)
        code, text = run(cp, tmp, ["-Dsuite.out=results/suite", "-Dsuite.threads=1," + many], ["--test", "--scenarios=S1"])
        check(code == 2 and "REFUSED" in text, "a sweep beyond the machine's cores is refused with exit 2 (exit %d)" % code)
        (tmp / "datasets" / "example_seq.txt").write_text("x[y] -1 -2\n", encoding="utf-8")
        before = set(out.iterdir()) if out.exists() else set()
        code, text = run(cp, tmp, ["-Dsuite.out=results/suite", "-Dsuite.cell=EMPTY"], ["--test", "--scenarios=S1"])
        new = [p for p in out.iterdir() if p not in before and "EMPTY" in p.name]
        check(code == 3, "a dataset read as empty stops the run with exit 3 (exit %d)" % code)
        check(len(new) == 1 and not (new[0] / "DONE").exists(), "and leaves no DONE marker")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    bad = [m for ok, m in results if not ok]
    print("suite runner check: %d/%d checks passed%s" % (len(results) - len(bad), len(results),
                                                         "" if not bad else "; FAILED: " + " | ".join(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
