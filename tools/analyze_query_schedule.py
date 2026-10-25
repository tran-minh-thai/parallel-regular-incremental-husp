#!/usr/bin/env python3
"""Evaluate the pre-registered selector hypotheses H1-H3 on query-schedule probe runs.

Reads every results-probe/<run>/ whose run_meta.json command matches the given dataset names,
groups runs by (dataset, schedule = dOld fraction) and policy (remine = F, sel = SEL,
keep-sfx-gf = K), and prints:

  H1  Spearman rank correlation between r = delta * |W| (computed from the window length and the
      dataset's delta) and the discovery candidate count of K, per (dataset, schedule) with at least
      five completed K queries; criterion <= -0.7.
  H2  total cost (seed + ingest + query) of SEL against min(K, F); criterion SEL <= 1.2 * min(K, F)
      when both pure policies complete, and SEL completes when K does not.
  H3  whether K is cheaper than F on some measured-value dataset and schedule.

A policy is "complete" when its run wrote one exactness row per scheduled query and every row
agrees with the reference. Exactness counts (queries compared, disagreements) are printed with
their denominators. Timings are feasibility probes from the development machine.

Usage: python3 tools/analyze_query_schedule.py results-probe NAME:delta:measured [NAME:delta:measured ...]
       e.g. ONLINE-RETAIL-II-H:0.001:yes FIFA:0.04:no
"""
import csv
import glob
import json
import os
import re
import sys
from collections import defaultdict


def spearman(xs, ys):
    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        i = 0
        while i < len(v):
            j = i
            while j + 1 < len(v) and v[order[j + 1]] == v[order[i]]:
                j += 1
            for k in range(i, j + 1):
                r[order[k]] = (i + j) / 2.0 + 1
            i = j + 1
        return r
    rx, ry = ranks(xs), ranks(ys)
    n = len(xs)
    mx, my = sum(rx) / n, sum(ry) / n
    cov = sum((a - mx) * (b - my) for a, b in zip(rx, ry))
    vx = sum((a - mx) ** 2 for a in rx) ** 0.5
    vy = sum((b - my) ** 2 for b in ry) ** 0.5
    return cov / (vx * vy) if vx and vy else float("nan")


def main():
    root = sys.argv[1]
    specs = {}
    for s in sys.argv[2:]:
        name, delta, measured = s.split(":")
        specs[name] = (float(delta), measured == "yes")
    runs = defaultdict(dict)                # (dataset, dold) -> policy -> record
    for meta_path in sorted(glob.glob(os.path.join(root, "*", "run_meta.json"))):
        meta = json.load(open(meta_path))
        cmd = meta["command"]
        m = re.search(r"QueryScheduleProbe datasets/(\S+)_seq\.txt \S+ (\S+) (\S+) (\d+) (\S+) (\S+)$", cmd)
        if not m or m.group(1) not in specs:
            continue
        name, k, schedule, policy = m.group(1), int(m.group(4)), m.group(5), m.group(6)
        dold = re.search(r"-Ddold=(\S+)", cmd)
        dold = dold.group(1) if dold else "0.25"
        d = os.path.dirname(meta_path)
        ex = list(csv.DictReader(open(os.path.join(d, "exactness.csv"))))
        tm = list(csv.DictReader(open(os.path.join(d, "timing_feasibility.csv"))))
        expected = k - 1 if schedule == "every" else None
        bad = sum(1 for r in ex if int(r["missing"]) + int(r["extra"]) + int(r["utility_mismatch"])
                  + int(r["period_mismatch"]) > 0)
        complete = expected is not None and len(ex) == expected and bad == 0
        cost = (int(tm[0]["seed_ms"] or 0) if tm else 0) + sum(int(r["ingest_ms"]) + int(r["query_ms"]) for r in tm)
        runs[(name, dold)][policy] = dict(ex=ex, tm=tm, complete=complete, cost=cost, bad=bad,
                                          rows=len(ex), expected=expected, run=os.path.basename(d),
                                          reference=meta.get("command", ""))
    h3 = []
    for (name, dold), pols in sorted(runs.items()):
        delta, measured = specs[name]
        print("== %s  dOld=%s  (values %s)" % (name, dold, "measured" if measured else "generated"))
        for pol, rec in sorted(pols.items()):
            print("   %-12s run=%s queries=%d/%s disagreeing=%d complete=%s total_ms=%d"
                  % (pol, rec["run"], rec["rows"], rec["expected"], rec["bad"], rec["complete"], rec["cost"]))
        k = pols.get("keep-sfx-gf")
        if k and len(k["ex"]) >= 5:
            rs = [delta * int(r["increment_seqs"]) for r in k["ex"]]
            cs = [int(r["candidates"]) for r in k["ex"]]
            rho = spearman(rs, cs)
            print("   H1 spearman(r, candidates) over %d K queries = %.3f -> %s" % (len(rs), rho, "PASS" if rho <= -0.7 else "FAIL"))
        else:
            print("   H1 not testable (K has fewer than 5 queries)")
        f, s = pols.get("remine"), pols.get("sel")
        if s is None or f is None:
            print("   H2 not testable (missing run)")
        elif k and k["complete"] and f["complete"]:
            best = min(k["cost"], f["cost"])
            ok = s["complete"] and s["cost"] <= 1.2 * best
            print("   H2 SEL=%d min(K,F)=%d ratio=%.2f -> %s" % (s["cost"], best, s["cost"] / best, "PASS" if ok else "FAIL"))
        else:
            print("   H2 K incomplete -> SEL must complete: %s" % ("PASS" if s["complete"] else "FAIL"))
        if k and f and k["complete"] and f["complete"]:
            win = k["cost"] < f["cost"]
            print("   K vs F: %d vs %d -> %s" % (k["cost"], f["cost"], "K cheaper" if win else "F cheaper"))
            if win and measured:
                h3.append((name, dold))
        if s:
            acts = [r.get("action", "") for r in s["ex"]]
            print("   SEL actions: discover=%d remine=%d" % (acts.count("discover"), acts.count("remine")))
    print("H3 (K cheaper than F on a measured-value dataset): %s %s" % ("PASS" if h3 else "FAIL", h3))


if __name__ == "__main__":
    main()
