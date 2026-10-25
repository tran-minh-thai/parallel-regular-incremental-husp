#!/usr/bin/env python3
"""Evaluate the pre-registered hypotheses H4-H5 for cost-capped discovery with exact fallback.

For every (dataset, dOld) with a cost-capped run (policy bud), the matching single-policy runs of
K (keep-sfx-gf) and F (remine) supply the per-query costs K_q and F_q (latest run of each policy;
K_q is infinite when K did not finish that query). F_hat_q is the cost of the last re-mine the
cost-capped run itself performed before query q (its discovery_mine_ms column on rows whose action
is remine or aborted).

  H4  per query q >= 1: cost_q <= 1.15 * min(K_q, c * F_hat_q + F_q) + 50 ms; query 0 (always a
      re-mine) against 1.15 * F_0 + 50 ms. Pass when at least 95 % of queries satisfy it.
  H5  totals (seed + ingest + queries), datasets other than FIFA: BUD <= 1.2 * min(K, F) when K and
      F both finish, otherwise BUD finishes and BUD <= 1.2 * F.

Usage: python3 tools/analyze_cost_capped.py results-probe c NAME [NAME ...]
"""
import csv
import glob
import json
import os
import re
import sys
from collections import defaultdict


def load(root, names):
    runs = defaultdict(lambda: defaultdict(list))     # (name, dold) -> policy -> [records], oldest first
    for meta_path in sorted(glob.glob(os.path.join(root, "*", "run_meta.json"))):
        cmd = json.load(open(meta_path))["command"]
        m = re.search(r"QueryScheduleProbe datasets/(\S+)_seq\.txt \S+ \S+ \S+ (\d+) every (\S+)$", cmd)
        if not m or m.group(1) not in names:
            continue
        dold = re.search(r"-Ddold=(\S+)", cmd)
        dold = dold.group(1) if dold else "0.25"
        d = os.path.dirname(meta_path)
        ex = list(csv.DictReader(open(os.path.join(d, "exactness.csv"))))
        tm = list(csv.DictReader(open(os.path.join(d, "timing_feasibility.csv"))))
        expected = int(m.group(2)) - 1
        bad = sum(1 for r in ex if int(r["missing"]) + int(r["extra"]) + int(r["utility_mismatch"])
                  + int(r["period_mismatch"]) > 0)
        runs[(m.group(1), dold)][m.group(3)].append(dict(
            run=os.path.basename(d), ex=ex, tm=tm, expected=expected, bad=bad,
            complete=len(ex) == expected and bad == 0,
            total=(int(tm[0]["seed_ms"] or 0) if tm else 0) + sum(int(r["ingest_ms"]) + int(r["query_ms"]) for r in tm)))
    return runs


def main():
    root, c, names = sys.argv[1], float(sys.argv[2]), set(sys.argv[3:])
    runs = load(root, names)
    h4_ok = h4_n = 0
    h5 = []
    for (name, dold), pols in sorted(runs.items()):
        if "bud" not in pols:
            continue
        b = pols["bud"][-1]
        f = pols.get("remine", [None])[-1]
        k = pols.get("keep-sfx-gf", [None])[-1]
        print("== %s dOld=%s  bud=%s queries=%d/%d disagreeing=%d" % (name, dold, b["run"], len(b["ex"]), b["expected"], b["bad"]))
        if f is None:
            print("   no F run: H4/H5 not testable")
            continue
        acts = [r["action"] for r in b["ex"]]
        print("   actions: discover=%d remine=%d aborted=%d" % (acts.count("discover"), acts.count("remine"), acts.count("aborted")))
        fhat = None
        ok = n = 0
        worst = 0.0
        for q, (row, t) in enumerate(zip(b["ex"], b["tm"])):
            cost = int(t["query_ms"])
            fq = int(f["tm"][q]["query_ms"]) if q < len(f["tm"]) else None
            kq = int(k["tm"][q]["query_ms"]) if (k and q < len(k["tm"])) else float("inf")
            if fq is None:
                continue
            bound = fq if q == 0 or fhat is None else min(kq, c * fhat + fq)
            n += 1
            sat = cost <= 1.15 * bound + 50
            ok += sat
            if bound > 0:
                worst = max(worst, cost / bound)
            if not sat:
                print("   q%d violates: cost=%d bound=%.0f (K=%s F=%d Fhat=%s action=%s)" % (q, cost, bound, kq, fq, fhat, row["action"]))
            if row["action"] in ("remine", "aborted"):
                fhat = int(t["discovery_mine_ms"])
        h4_ok += ok
        h4_n += n
        print("   H4 per-query bound satisfied %d/%d, worst cost/bound = %.2f" % (ok, n, worst))
        if name == "FIFA":
            print("   H5 not applied (FIFA formed the hypothesis)")
            continue
        if k and k["complete"] and f["complete"]:
            best = min(k["total"], f["total"])
            res = b["complete"] and b["total"] <= 1.2 * best
            print("   H5 BUD=%d K=%d F=%d ratio to best=%.2f -> %s" % (b["total"], k["total"], f["total"], b["total"] / best, "PASS" if res else "FAIL"))
        else:
            res = b["complete"] and b["total"] <= 1.2 * f["total"]
            print("   H5 K incomplete: BUD=%d F=%d ratio=%.2f -> %s" % (b["total"], f["total"], b["total"] / f["total"], "PASS" if res else "FAIL"))
        h5.append(res)
    print("H4 overall: %d/%d queries (%.1f%%) -> %s" % (h4_ok, h4_n, 100.0 * h4_ok / max(1, h4_n), "PASS" if h4_n and h4_ok >= 0.95 * h4_n else "FAIL"))
    print("H5 overall: %d/%d settings -> %s" % (sum(h5), len(h5), "PASS" if h5 and all(h5) else "FAIL"))


if __name__ == "__main__":
    main()
