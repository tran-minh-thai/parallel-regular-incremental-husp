#!/usr/bin/env python3
"""Summarise the reserve-budget experiment (E2B) and generate the repeats it needs.

    python3 tools/summarize_budget.py [--config experiments/campaign_9950x_budget.json] [--write-repeats]
    python3 tools/summarize_budget.py --self-test

Reads <out>/cells.tsv of the configuration and every recorded cell of the experiments whose name starts with E2B.
Every cell runs F (remine-nogc), bud3fs, bud3rs and bud3rb in one JVM, in an order that rotates with the repetition;
answers are compared with the first policy of the JVM. Per (dataset, schedule) and policy: the total cost (seed +
ingest + query, milliseconds) and its ratio to F of the same run, averaged over the repetitions. Checks, each printed
with its denominator:

  B1  every query of the three policies after the first agrees with the first (all four policies agree);
  B2  tools/check_reserve.py on every bud3rs and bud3rb run (reserve, budget, deadline, gate, cost bound);
  B3  each pair of "d5_pairs": bud3rb/F <= bud3fs/F + 0.05;
  B4  each pair of "d4_pairs" and "d4_tie": bud3rb/F <= bud3fs/F (bud3rb/F against bud3rs/F is printed, no threshold);
  B5  every run: bud3rb total <= 1.15 * (1.10 * F total + max F_hat) + 50 ms per query;
  B6  refusals of bud3rb: the reserve check's refusal rules (bud3rs would refuse too; runs within their bound);
  B7  per policy, total at the first position of the JVM over total at the last, on pairs with all four positions
      (descriptive, no threshold).

--write-repeats stores experiment E2BR (two more repetitions, rotation continued) for every pair with fewer than three
runs whose bud3rb/bud3fs, bud3rb/bud3rs or bud3rb/F lies within 10% of 1. Output: <out>/summary_E2B.tsv and the printed
report. Exit status 1 when a check fails, a cell cannot be read, or a check had nothing to compare.
"""
import argparse
import contextlib
import csv
import io
import json
import statistics as st
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import check_reserve  # noqa: E402
from summarize_reserve import run_totals, cv  # noqa: E402

CONFIG = ROOT / "experiments" / "campaign_9950x_budget.json"
POLICIES = ("remine-nogc", "bud3fs", "bud3rs", "bud3rb")


def summarize(cfg, root, write_repeats=False, config_path=None):
    out = root / cfg["out"]
    ledger = list(csv.DictReader(open(out / "cells.tsv", encoding="utf-8", newline=""), delimiter="\t")) \
        if (out / "cells.tsv").exists() else []
    problems, failures, lines = [], [], []
    groups = defaultdict(list)
    b1_n = b1_bad = b2_n = b6_n = 0
    for r in ledger:
        if not r["experiment"].startswith("E2B"):
            continue
        if r["status"] != "OK":
            problems.append("%s: status %s" % (r["key"], r["status"]))
            continue
        d = root / r["run_dir"]
        order = r["policies"].split(",")
        try:
            t = run_totals(d)
            for name, rows in check_reserve.read_run(d).items():
                k, pr, _ = check_reserve.check_policy(name, rows)
                b2_n += k
                if name.startswith("bud3rb"):
                    b6_n += sum(1 for x in rows if x["action"] == "gated")
                for x in pr:
                    tag = "B6" if ("refusals in a row" in x or "bud3rs would attempt" in x) else "B2"
                    failures.append(tag + " " + x)
        except Exception as e:
            problems.append("%s: cannot read %s (%s)" % (r["key"], d.as_posix(), e))
            continue
        if sorted(t) != sorted(POLICIES):
            problems.append("%s: policies %s, expected %s" % (r["key"], sorted(t), sorted(POLICIES)))
            continue
        for pol in order[1:]:
            b1_n += t[pol]["queries"]
            b1_bad += t[pol]["bad"]
        groups[(r["dataset"], r["schedule"])].append((order, t))
    if b1_bad:
        failures.append("B1: %d of %d queries disagree with the first policy of their JVM" % (b1_bad, b1_n))
    d4 = {tuple(p) for p in cfg.get("d4_pairs", [])} | {tuple(p) for p in cfg.get("d4_tie", [])}
    d5 = {tuple(p) for p in cfg.get("d5_pairs", [])}
    counts = defaultdict(lambda: [0, 0])
    near = []
    position = defaultdict(list)                       # policy -> [total at first position / total at last position]
    for key in sorted(groups):
        runs = groups[key]
        ratio = {p: [t[p]["total"] / t["remine-nogc"]["total"] for _, t in runs] for p in POLICIES[1:]}
        m = {p: st.mean(v) for p, v in ratio.items()}
        verdicts = []
        ok5 = True
        for _, t in runs:
            bound = 1.15 * (1.10 * t["remine-nogc"]["total"] + t["bud3rb"]["fhat"]) + 50 * t["bud3rb"]["queries"]
            ok5 = ok5 and t["bud3rb"]["total"] <= bound
        counts["B5"][0] += 1
        counts["B5"][1] += ok5
        verdicts.append("B5 %s" % ("ok" if ok5 else "FAIL"))
        if not ok5:
            failures.append("B5 %s %s" % key)
        if key in d5:
            ok = m["bud3rb"] <= m["bud3fs"] + 0.05
            counts["B3"][0] += 1
            counts["B3"][1] += ok
            verdicts.append("B3 %s" % ("ok" if ok else "FAIL"))
            if not ok:
                failures.append("B3 %s %s: bud3rb/F %.3f > bud3fs/F %.3f + 0.05" % (key + (m["bud3rb"], m["bud3fs"])))
        if key in d4:
            ok = m["bud3rb"] <= m["bud3fs"]
            counts["B4"][0] += 1
            counts["B4"][1] += ok
            verdicts.append("B4 %s" % ("ok" if ok else "FAIL"))
            if not ok:
                failures.append("B4 %s %s: bud3rb/F %.3f > bud3fs/F %.3f" % (key + (m["bud3rb"], m["bud3fs"])))
        if len(runs) >= len(POLICIES):
            for p in POLICIES:
                first = [t[p]["total"] for o, t in runs if o[0] == p]
                last = [t[p]["total"] for o, t in runs if o[-1] == p]
                if first and last:
                    position[p].append(st.mean(first) / st.mean(last))
        rb = st.mean(t["bud3rb"]["total"] for _, t in runs)
        if len(runs) < 3 and any(abs(1 - rb / st.mean(t[p]["total"] for _, t in runs)) < 0.10
                                 for p in ("bud3fs", "bud3rs", "remine-nogc")):
            near.append(list(key))
        lines.append([key[0], key[1], len(runs), "%.0f" % st.mean(t["remine-nogc"]["total"] for _, t in runs)]
                     + ["%.3f" % m[p] for p in POLICIES[1:]] + ["%.3f" % cv(ratio["bud3rb"]), "; ".join(verdicts)])
    head = ["dataset", "schedule", "runs", "F_ms", "bud3fs_over_F", "bud3rs_over_F", "bud3rb_over_F", "cv_bud3rb_over_F", "checks"]
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "summary_E2B.tsv", "w", encoding="utf-8", newline="\n") as f:
        for l in [head] + lines:
            f.write("\t".join(str(x) for x in l) + "\n")
    for l in [head] + lines:
        print("  ".join(str(x) for x in l))
    print("B1: %d queries of the policies after the first compared with the first, %d disagreeing" % (b1_n, b1_bad))
    print("B2: %d bud3rs and bud3rb queries checked against the reserve rules" % b2_n)
    for k in ("B3", "B4", "B5"):
        print("%s: %d of %d pairs as predicted" % (k, counts[k][1], counts[k][0]))
    print("B6: %d bud3rb refusals checked" % b6_n)
    for p in POLICIES:
        v = position[p]
        print("B7: %s total at the first position over the last: %s over %d pairs" % (
            p, "mean %.3f, range %.3f-%.3f" % (st.mean(v), min(v), max(v)) if v else "-", len(v)))
    for k, n in (("B1", b1_n), ("B2", b2_n), ("B5", counts["B5"][0])):
        if n == 0:
            failures.append("%s: nothing compared" % k)
    print("pairs needing repeats: %d %s" % (len(near), near))
    if write_repeats and near:
        base = cfg["experiments"]["E2B"]
        cfg["experiments"]["E2BR"] = {"about": "generated by tools/summarize_budget.py: pairs near a tie with fewer than three runs",
                                      "pairs": near, "policies": base["policies"], "oracle": base["oracle"], "rotate": True,
                                      "first_rep": 1, "reps": 2, "limit_s": base["limit_s"], "props": base.get("props", [])}
        with open(config_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(cfg, indent=2) + "\n")
        print("-> experiment E2BR written to %s" % Path(config_path).as_posix())
    for p in problems:
        print("PROBLEM " + p)
    for x in failures:
        print("FAILED " + x)
    return 1 if failures or problems else 0


def self_test():
    """A fixture of three pairs (one with a full rotation), then injected defects; each must produce its outcome."""
    def cell_rows(totals, order, bad=0):
        ex, tm = [], []
        for pol in order:
            for q in range(2):
                e = {"policy": pol, "missing": str(bad if (q == 1 and pol == order[-1]) else 0), "extra": "0",
                     "utility_mismatch": "0", "period_mismatch": "0", "query": str(q), "action": "", "fhat_us": "",
                     "charge_us": "", "reserve_us": "", "gate": "", "attempt_budget_us": "", "floor_us": "", "budget_ms": ""}
                if pol in ("bud3rs", "bud3rb"):
                    e.update(action="seed" if q == 0 else "skipped", fhat_us="0" if q == 0 else "100000", charge_us="0",
                             reserve_us="0" if q == 0 else "10000", gate="0", attempt_budget_us="0" if pol == "bud3rb" else "",
                             floor_us="0" if pol == "bud3rb" else "")
                ex.append(e)
                tm.append({"seed_ms": "", "ingest_ms": "0", "query_ms": str(totals[pol] // 2)})
        return ex, tm

    def build(tmp, cells):
        out = Path(tmp) / "results" / "x"
        out.mkdir(parents=True)
        head = ["key", "experiment", "dataset", "schedule", "policies", "threads", "rep", "status", "exit_code", "run_dir"]
        rows = []
        for i, (exp, ds, sch, rep, totals, kw) in enumerate(cells):
            k = rep % 4
            order = list(POLICIES[k:] + POLICIES[:k])
            d = out / ("run%d" % i)
            d.mkdir()
            ex, tm = cell_rows(totals, order, **kw)
            for name, data in (("exactness.csv", ex), ("timing_feasibility.csv", tm)):
                with open(d / name, "w", encoding="utf-8", newline="") as f:
                    w = csv.DictWriter(f, fieldnames=list(data[0].keys()))
                    w.writeheader()
                    w.writerows(data)
            rows.append(["%s|%s|%s|r%d" % (exp, ds, sch, rep), exp, ds, sch, ",".join(order), "16", str(rep), "OK", "0",
                         "results/x/run%d" % i])
        with open(out / "cells.tsv", "w", encoding="utf-8", newline="") as f:
            f.write("\t".join(head) + "\n" + "\n".join("\t".join(r) for r in rows) + "\n")

    cfg = {"out": "results/x", "d4_pairs": [["A", "S1"]], "d4_tie": [], "d5_pairs": [["B", "S1"]],
           "experiments": {"E2B": {"policies": list(POLICIES), "oracle": "first", "limit_s": 60}}}
    tot = lambda f, fs, rs, rb: {"remine-nogc": f, "bud3fs": fs, "bud3rs": rs, "bud3rb": rb}
    clean = [("E2B", "A", "S1", r, tot(1000, 1500, 1100, 1150), {}) for r in range(4)] + \
            [("E2B", "B", "S1", 0, tot(10000, 4000, 8000, 4100), {})] + \
            [("E2B", "C", "S2", 0, tot(10000, 9800, 9900, 9950), {})]
    cases = []

    def case(name, cells, expect_rc, must_print=()):
        with tempfile.TemporaryDirectory() as tmp:
            build(tmp, cells)
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = summarize(json.loads(json.dumps(cfg)), Path(tmp))
            text = buf.getvalue()
        ok = rc == expect_rc and all(s in text for s in must_print)
        cases.append((name, ok, "exit %d" % rc))

    case("clean fixture", clean, 0, ("B3: 1 of 1", "B4: 1 of 1", "B5: 3 of 3", "over 1 pairs",
                                     "pairs needing repeats: 2 [['B', 'S1'], ['C', 'S2']]"))
    bad = list(clean)
    bad[1] = ("E2B", "A", "S1", 1, tot(1000, 1500, 1100, 1150), {"bad": 1})
    case("defect: one query disagrees with the first policy (B1)", bad, 1, ("FAILED B1",))
    bad = list(clean)
    bad[4] = ("E2B", "B", "S1", 0, tot(10000, 4000, 8000, 8000), {})
    case("defect: bud3rb loses the win (B3)", bad, 1, ("FAILED B3",))
    bad = [("E2B", "A", "S1", r, tot(1000, 1100, 1100, 1200), {}) for r in range(4)] + clean[4:]
    case("defect: bud3rb worse than bud3fs on a D4 pair (B4)", bad, 1, ("FAILED B4",))
    bad = list(clean)
    bad[5] = ("E2B", "C", "S2", 0, tot(10000, 9800, 9900, 30000), {})
    case("defect: bud3rb above the B5 bound", bad, 1, ("FAILED B5",))
    case("vacuous: empty ledger is not a pass", [], 1, ("nothing compared",))
    for name, ok, detail in cases:
        print("%-60s %s  %s" % (name, "ok" if ok else "FAILED", detail))
    badc = [c for c in cases if not c[1]]
    print("self-test: %d/%d cases as expected" % (len(cases) - len(badc), len(cases)))
    return 1 if badc else 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(CONFIG))
    ap.add_argument("--write-repeats", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    cfg = json.loads(Path(args.config).read_text(encoding="utf-8"))
    return summarize(cfg, ROOT, args.write_repeats, args.config)


if __name__ == "__main__":
    sys.exit(main())
