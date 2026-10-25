#!/usr/bin/env python3
"""Evaluate the pre-registered hypotheses H8, H9a and H9b (cost-capped discovery with window backoff).

Each run holds remine-nogc (F) and bud3 (BUD3) in one JVM. Per run:

  H8   (a) within each anchor epoch (an epoch ends when the window length drops, i.e. after an anchor
       reset), the number of aborted attempts <= ceil(log2(W_max / W_min)) + 1 over the windows of that
       epoch's attempts; (b) total BUD3 <= 1.15 * (sum F_q + aborts * c * max budget) + 50 ms * queries.
  H9a  total BUD3 <= 1.3 * total F, whenever F finishes.
  H9b  on VS-CHAIN96-CW with dOld 0.5: total BUD3 <= 0.6 * total F.

Usage: python3 tools/analyze_h89.py results-probe c NAME [NAME ...]
"""
import csv
import glob
import json
import math
import os
import re
import sys
from collections import defaultdict


def main():
    root, c, names = sys.argv[1], float(sys.argv[2]), set(sys.argv[3:])
    h8 = []
    h9a = []
    h9b = []
    for meta_path in sorted(glob.glob(os.path.join(root, "*", "run_meta.json"))):
        cmd = json.load(open(meta_path))["command"]
        m = re.search(r"QueryScheduleProbe datasets/(\S+)_seq\.txt \S+ \S+ \S+ (\d+) every remine-nogc,bud3$", cmd)
        if not m or m.group(1) not in names:
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
        f, b = by.get("remine-nogc", []), by.get("bud3", [])

        def stats(rows):
            bad = sum(1 for e, _ in rows if int(e["missing"]) + int(e["extra"]) + int(e["utility_mismatch"])
                      + int(e["period_mismatch"]) > 0)
            total = (int(rows[0][1]["seed_ms"] or 0) if rows else 0) + sum(int(t["ingest_ms"]) + int(t["query_ms"]) for _, t in rows)
            return bad, total, len(rows) == expected and bad == 0
        fbad, ftot, fdone = stats(f)
        bbad, btot, bdone = stats(b)
        print("== %s dOld=%s run=%s" % (name, dold, os.path.basename(d)))
        print("   F    queries=%d/%d disagreeing=%d finished=%s total_ms=%d" % (len(f), expected, fbad, fdone, ftot))
        print("   BUD3 queries=%d/%d disagreeing=%d finished=%s total_ms=%d" % (len(b), expected, bbad, bdone, btot))
        acts = [e.get("action", "") for e, _ in b]
        print("   actions: discover=%d aborted=%d skipped=%d remine=%d; abort phases=%s"
              % (acts.count("discover"), acts.count("aborted"), acts.count("skipped"), acts.count("remine"),
                 [e.get("abort_phase") for e, _ in b if e.get("action") == "aborted"]))
        # H8a per epoch
        epochs, cur, prev_w = [], [], -1
        for e, _ in b:
            w = int(e["increment_seqs"] or 0)
            if prev_w >= 0 and w < prev_w:
                epochs.append(cur)
                cur = []
            prev_w = w
            cur.append(e)
        epochs.append(cur)
        ok_a = True
        for ep in epochs:
            att = [int(e["increment_seqs"]) for e in ep if e.get("action") in ("discover", "aborted")]
            ab = sum(1 for e in ep if e.get("action") == "aborted")
            if att and ab:
                bound = math.ceil(math.log2(max(att) / min(att))) + 1 if min(att) > 0 else float("inf")
                print("   epoch: attempts=%d aborts=%d windows=%d..%d log-bound=%d" % (len(att), ab, min(att), max(att), bound))
                ok_a = ok_a and ab <= bound
        aborts = acts.count("aborted")
        maxbud = max([int(e.get("budget_ms") or 0) for e, _ in b] + [0])
        fsum = sum(int(t["query_ms"]) for _, t in f)
        bound_b = 1.15 * (fsum + aborts * c * maxbud) + 50 * len(b)
        bq = sum(int(t["query_ms"]) for _, t in b)
        ok_b = bq <= bound_b
        print("   H8 aborts within log bound: %s; query cost %d <= %.0f: %s" % (ok_a, bq, bound_b, ok_b))
        h8.append(ok_a and ok_b)
        if fdone:
            r = btot / max(1, ftot)
            ok = bdone and r <= 1.3
            print("   H9a BUD3/F = %.2f -> %s" % (r, "PASS" if ok else "FAIL"))
            h9a.append(ok)
            if name == "VS-CHAIN96-CW" and dold == "0.5":
                okb = bdone and r <= 0.6
                print("   H9b BUD3/F = %.2f -> %s" % (r, "PASS" if okb else "FAIL"))
                h9b.append(okb)
        else:
            print("   F did not finish: H9a not testable")
    print("H8: %d/%d -> %s" % (sum(h8), len(h8), "PASS" if h8 and all(h8) else "FAIL"))
    print("H9a: %d/%d -> %s" % (sum(h9a), len(h9a), "PASS" if h9a and all(h9a) else "FAIL"))
    print("H9b: %d/%d -> %s" % (sum(h9b), len(h9b), "PASS" if h9b and all(h9b) else "FAIL"))


if __name__ == "__main__":
    main()
