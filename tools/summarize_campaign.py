#!/usr/bin/env python3
"""Summarise the main experiment of the measurement campaign and generate the repeats it needs.

    python3 tools/summarize_campaign.py [--out results/9950x] [--write-repeats] [--margin 0.10]

Reads <out>/cells.tsv and, for every recorded cell of E2 and its repeats (E2R), the run directory's
exactness.csv and timing_feasibility.csv. Per (dataset, schedule) it reports the total cost of F (re-mine at
query) and BUD3 (seed + ingest + query, milliseconds, mean over repetitions), their ratio, BUD3's actions,
and the checks of the pre-registered hypotheses:

  P1  every BUD3 query agrees with F of the same run (the in-run reference);
  P2  within each anchor epoch, aborts <= ceil(log2(W_max / W_min)) + 1, and the BUD3 query cost is at most
      1.15 * (sum F_q + aborts * max budget) + 50 ms per query;
  P3  costly datasets, schedule S1: BUD3 <= 0.7 * F;
  P4  cheap datasets: BUD3 <= sum F + aborts * max budget (the additive allowance of P2), no ratio.

Every comparison prints its denominator. Cells whose ratio lies within the margin of 1 (rule: a ranking
inside the noise needs repetitions) are listed; --write-repeats stores them as experiment E2R (two more
repetitions, indices 1 and 2) in experiments/campaign_9950x.json, which tools/campaign.py then runs.
Output: <out>/summary_E2.tsv and the printed report. Exit status is non-zero when a hypothesis fails or a
recorded cell cannot be read.
"""
import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "experiments" / "campaign_9950x.json"


def read_tsv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def policy_rows(run_dir):
    ex = read_csv(run_dir / "exactness.csv")
    tm = read_csv(run_dir / "timing_feasibility.csv")
    by = defaultdict(list)
    for e, t in zip(ex, tm):
        by[e["policy"]].append((e, t))
    return by


def total_ms(rows):
    if not rows:
        return None
    seed = int(rows[0][1]["seed_ms"] or 0)
    return seed + sum(int(t["ingest_ms"]) + int(t["query_ms"]) for _, t in rows)


def p2_check(f_rows, b_rows):
    """Aborts within the log bound per anchor epoch, and the additive cost bound; returns (ok, detail)."""
    epochs, cur, prev = [], [], -1
    for e, _ in b_rows:
        w = int(e["increment_seqs"] or 0)
        if prev >= 0 and w < prev:
            epochs.append(cur)
            cur = []
        prev = w
        cur.append(e)
    epochs.append(cur)
    ok_log, parts = True, []
    for ep in epochs:
        att = [int(e["increment_seqs"]) for e in ep if e.get("action") in ("discover", "aborted")]
        ab = sum(1 for e in ep if e.get("action") == "aborted")
        if att and ab:
            bound = math.ceil(math.log2(max(att) / min(att))) + 1 if min(att) > 0 else float("inf")
            parts.append("%d/%d" % (ab, bound))
            ok_log = ok_log and ab <= bound
    aborts = sum(1 for e, _ in b_rows if e.get("action") == "aborted")
    maxbud = max([int(e.get("budget_ms") or 0) for e, _ in b_rows] + [0])
    fsum = sum(int(t["query_ms"]) for _, t in f_rows)
    bq = sum(int(t["query_ms"]) for _, t in b_rows)
    bound = 1.15 * (fsum + aborts * maxbud) + 50 * len(b_rows)
    return ok_log and bq <= bound, "aborts per epoch %s; query cost %d <= %.0f" % (",".join(parts) or "none", bq, bound), \
        fsum + aborts * maxbud


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="results/9950x")
    ap.add_argument("--write-repeats", action="store_true")
    ap.add_argument("--margin", type=float, default=0.10)
    ap.add_argument("--config", default=str(CONFIG))
    args = ap.parse_args(argv)
    config = Path(args.config)
    cfg = json.loads(config.read_text(encoding="utf-8"))
    out = ROOT / args.out
    ledger = read_tsv(out / "cells.tsv") if (out / "cells.tsv").exists() else []
    groups = defaultdict(list)
    problems = []
    for r in ledger:
        if r["experiment"] not in ("E2", "E2R"):
            continue
        if r["status"] != "OK":
            problems.append("%s: status %s" % (r["key"], r["status"]))
            continue
        d = ROOT / r["run_dir"]
        try:
            groups[(r["dataset"], r["schedule"])].append(policy_rows(d))
        except Exception as e:
            problems.append("%s: cannot read %s (%s)" % (r["key"], d.as_posix(), e))
    lines, near, failures = [], [], []
    p1_n = p1_bad = 0
    for (ds, sch), runs in sorted(groups.items()):
        role = cfg["datasets"][ds]["role"]
        f_tot, b_tot, acts, p2_ok, p2_det, allow = [], [], [], True, "", 0
        for by in runs:
            f, b = by.get("remine-nogc", []), by.get("bud3", [])
            for e, _ in b:
                p1_n += 1
                if int(e["missing"]) + int(e["extra"]) + int(e["utility_mismatch"]) + int(e["period_mismatch"]) > 0:
                    p1_bad += 1
            f_tot.append(total_ms(f))
            b_tot.append(total_ms(b))
            a = [e.get("action", "") for e, _ in b]
            acts.append("d%d/a%d/s%d/r%d" % (a.count("discover"), a.count("aborted"), a.count("skipped"), a.count("remine")))
            ok, det, al = p2_check(f, b)
            p2_ok, p2_det, allow = p2_ok and ok, det, max(allow, al)
        fm, bm = sum(f_tot) / len(f_tot), sum(b_tot) / len(b_tot)
        ratio = bm / fm if fm else float("nan")
        verdicts = ["P2 %s" % ("ok" if p2_ok else "FAIL")]
        if not p2_ok:
            failures.append("P2 %s %s: %s" % (ds, sch, p2_det))
        if role == "costly" and sch == "S1":
            ok = ratio <= 0.7
            verdicts.append("P3 %s" % ("ok" if ok else "FAIL"))
            if not ok:
                failures.append("P3 %s %s: ratio %.2f" % (ds, sch, ratio))
        if role == "cheap":
            ok = bm <= fm + allow
            verdicts.append("P4 %s" % ("ok" if ok else "FAIL"))
            if not ok:
                failures.append("P4 %s %s: BUD3 %.0f > F %.0f + %.0f" % (ds, sch, bm, fm, allow))
        if abs(1 - ratio) < args.margin and len(runs) < 3:
            near.append([ds, sch])
        lines.append([ds, role, sch, len(runs), "%.0f" % fm, "%.0f" % bm, "%.3f" % ratio, " ".join(acts), "; ".join(verdicts)])
    head = ["dataset", "role", "schedule", "runs", "F_ms", "BUD3_ms", "BUD3_over_F", "bud3_actions", "checks"]
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "summary_E2.tsv", "w", encoding="utf-8", newline="\n") as f:
        f.write("\t".join(head) + "\n")
        for l in lines:
            f.write("\t".join(str(x) for x in l) + "\n")
    for l in [head] + lines:
        print("  ".join(str(x) for x in l))
    print("P1: %d BUD3 queries compared with F of the same run, %d disagreeing" % (p1_n, p1_bad))
    if p1_bad or (groups and p1_n == 0):
        failures.append("P1: %d of %d queries disagree" % (p1_bad, p1_n))
    print("cells within %.0f%% of a tie needing repeats: %d %s" % (100 * args.margin, len(near), near))
    if args.write_repeats and near:
        spec = cfg["experiments"]["E2"]
        cfg["experiments"]["E2R"] = {"about": "generated by tools/summarize_campaign.py: E2 cells within the margin of a tie",
                                     "pairs": near, "policies": spec["policies"], "oracle": spec["oracle"],
                                     "first_rep": 1, "reps": 2, "limit_s": spec["limit_s"]}
        with open(config, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(cfg, indent=2) + "\n")
        print("-> experiment E2R written to %s" % config.as_posix())
    for p in problems:
        print("PROBLEM " + p)
    for x in failures:
        print("FAILED " + x)
    return 1 if failures or problems else 0


if __name__ == "__main__":
    sys.exit(main())
