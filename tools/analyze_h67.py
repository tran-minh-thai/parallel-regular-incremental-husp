#!/usr/bin/env python3
"""Evaluate the pre-registered hypotheses H6, H7a and H7b (cost-capped discovery, round 2).

Each probe run holds three policies in one JVM: remine-nogc (F), bud2 (BUD2) and keep-sfx-gf-nogc (K).
For every run whose command lists exactly those policies:

  H6   per query q >= 1 of BUD2: cost_q <= 1.15 * (c * F_hat_q + F_q) + 50 ms, F_q from the F rows of the
       same run, F_hat_q the cost of BUD2's own last full re-mine (discovery_mine_ms on rows whose action is
       remine, aborted or skipped). Pass when at least 95 % of queries across all runs satisfy it.
  H7a  datasets other than FIFA and the large ones: total BUD2 <= 1.2 * min(K, F) when K finishes,
       otherwise BUD2 finishes and BUD2 <= 1.2 * F.
  H7b  large measured datasets: total BUD2 < total F.

A policy finishes when it wrote one row per scheduled query and every row agrees with the reference.
Usage: python3 tools/analyze_h67.py results-probe c LARGE_NAME[,LARGE_NAME...] [NAME ...]
"""
import csv
import glob
import json
import os
import re
import sys
from collections import defaultdict

POLICIES = ("remine-nogc", "bud2", "keep-sfx-gf-nogc")


def main():
    root, c = sys.argv[1], float(sys.argv[2])
    large = set(sys.argv[3].split(","))
    names = large | set(sys.argv[4:])
    h6_ok = h6_n = 0
    h7a, h7b = [], []
    for meta_path in sorted(glob.glob(os.path.join(root, "*", "run_meta.json"))):
        cmd = json.load(open(meta_path))["command"]
        m = re.search(r"QueryScheduleProbe datasets/(\S+)_seq\.txt \S+ \S+ \S+ (\d+) every (\S+)$", cmd)
        if not m or m.group(1) not in names or tuple(m.group(3).split(",")) != POLICIES:
            continue
        name, expected = m.group(1), int(m.group(2)) - 1
        dold = re.search(r"-Ddold=(\S+)", cmd)
        dold = dold.group(1) if dold else "0.25"
        d = os.path.dirname(meta_path)
        ex = list(csv.DictReader(open(os.path.join(d, "exactness.csv"))))
        tm = list(csv.DictReader(open(os.path.join(d, "timing_feasibility.csv"))))
        by = defaultdict(list)
        for e, t in zip(ex, tm):
            by[e["policy"]].append((e, t))
        rec = {}
        for pol in POLICIES:
            rows = by.get(pol, [])
            bad = sum(1 for e, _ in rows if int(e["missing"]) + int(e["extra"]) + int(e["utility_mismatch"])
                      + int(e["period_mismatch"]) > 0)
            total = (int(rows[0][1]["seed_ms"] or 0) if rows else 0) + sum(int(t["ingest_ms"]) + int(t["query_ms"]) for _, t in rows)
            rec[pol] = dict(rows=rows, n=len(rows), bad=bad, done=len(rows) == expected and bad == 0, total=total)
        f, b, k = rec["remine-nogc"], rec["bud2"], rec["keep-sfx-gf-nogc"]
        print("== %s dOld=%s run=%s" % (name, dold, os.path.basename(d)))
        for pol in POLICIES:
            r = rec[pol]
            print("   %-17s queries=%d/%d disagreeing=%d finished=%s total_ms=%d" % (pol, r["n"], expected, r["bad"], r["done"], r["total"]))
        acts = [e.get("action", "") for e, _ in b["rows"]]
        print("   BUD2 actions: discover=%d remine=%d aborted=%d skipped=%d kept_on_abort=%s"
              % (acts.count("discover"), acts.count("remine"), acts.count("aborted"), acts.count("skipped"),
                 sum(int(e.get("kept_on_abort") or 0) for e, _ in b["rows"])))
        fhat, ok, n, worst = None, 0, 0, 0.0
        for q, (e, t) in enumerate(b["rows"]):
            if q >= len(f["rows"]):
                break
            fq = int(f["rows"][q][1]["query_ms"])
            cost = int(t["query_ms"])
            if q >= 1 and fhat is not None:
                bound = c * fhat + fq
                n += 1
                sat = cost <= 1.15 * bound + 50
                ok += sat
                worst = max(worst, cost / bound if bound else 0)
                if not sat:
                    print("   H6 violation q%d: cost=%d bound=%.0f (F=%d Fhat=%d action=%s)" % (q, cost, bound, fq, fhat, e.get("action")))
            if e.get("action") in ("remine", "aborted", "skipped"):
                fhat = int(t["discovery_mine_ms"])
        h6_ok += ok
        h6_n += n
        print("   H6 %d/%d queries within bound, worst cost/bound=%.2f" % (ok, n, worst))
        if name == "FIFA":
            continue
        if name in large:
            res = b["done"] and f["done"] and b["total"] < f["total"]
            print("   H7b BUD2=%d F=%d ratio=%.2f -> %s" % (b["total"], f["total"], b["total"] / max(1, f["total"]), "PASS" if res else "FAIL"))
            h7b.append(res)
        elif k["done"] and f["done"]:
            best = min(k["total"], f["total"])
            res = b["done"] and b["total"] <= 1.2 * best
            print("   H7a BUD2=%d K=%d F=%d ratio=%.2f -> %s" % (b["total"], k["total"], f["total"], b["total"] / best, "PASS" if res else "FAIL"))
            h7a.append(res)
        else:
            res = b["done"] and b["total"] <= 1.2 * f["total"]
            print("   H7a (K unfinished) BUD2=%d F=%d ratio=%.2f -> %s" % (b["total"], f["total"], b["total"] / max(1, f["total"]), "PASS" if res else "FAIL"))
            h7a.append(res)
    print("H6: %d/%d (%.1f%%) -> %s" % (h6_ok, h6_n, 100.0 * h6_ok / max(1, h6_n), "PASS" if h6_n and h6_ok >= 0.95 * h6_n else "FAIL"))
    print("H7a: %d/%d -> %s" % (sum(h7a), len(h7a), "PASS" if h7a and all(h7a) else "FAIL"))
    print("H7b: %d/%d -> %s" % (sum(h7b), len(h7b), "PASS" if h7b and all(h7b) else "FAIL"))


if __name__ == "__main__":
    main()
