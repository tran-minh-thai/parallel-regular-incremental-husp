#!/usr/bin/env python3
"""Summarise the heavy-sequence bound selector experiment (E2X) and generate the repeats it needs.

    python3 tools/summarize_selector.py [--config experiments/campaign_9950x_selector.json] [--write-repeats]
    python3 tools/summarize_selector.py --self-test

Reads <out>/cells.tsv and every recorded cell of the experiments whose name starts with E2X. Every cell runs F
(remine-nogc), bud3rb and bud3rbx in one JVM, in an order that rotates with the repetition; answers are compared with the
first policy of the JVM. Per (dataset, schedule) and policy: the total cost (seed + ingest + query, milliseconds) and its
ratio to F of the same run, averaged over the repetitions. Checks, each printed with its denominator:

  X0  every completed discovery of bud3rb and bud3rbx has output / enumeration time <= the selector rate, and so does the
      calibration file's rate (when the file is present);
  X1  every query of the policies after the first agrees with the first;
  X2  tools/check_reserve.py on every bud3rb and bud3rbx run (reserve replay, budgets, gate, cost bound, and the selector's
      decision against its logged bound); the logged bound against an independent recomputation is checked on the
      development machine (it needs the dataset), not here;
  X4  pairs where bud3rbx never skips in any run: bud3rbx/F <= bud3rb/F + 0.02; every bud3rbx run: sum selector_us <= 2% of F;
  X5  pairs where bud3rbx skips in every run: bud3rbx/F <= bud3rb/F;
  X6  every run: bud3rbx total <= 1.15 * (1.10 * F total + max F_hat) + 50 ms per query;
  X7  (descriptive) bud3rb/F here against bud3rb/F in E2B, per pair: mean difference and two standard errors.
X3 needs the bound of bud3rb's attempts, recomputed from the dataset, and is computed on the development machine.

--write-repeats stores experiment E2XR (two more repetitions, rotation continued) for every pair with fewer than three runs
whose bud3rbx/bud3rb or bud3rbx/F lies within 10% of 1. Output: <out>/summary_E2X.tsv and the printed report. Exit status 1
when a check fails, a cell cannot be read, or a check had nothing to compare.
"""
import argparse
import contextlib
import csv
import io
import json
import math
import statistics as st
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import check_reserve  # noqa: E402

CONFIG = ROOT / "experiments" / "campaign_9950x_selector.json"
POLICIES = ("remine-nogc", "bud3rb", "bud3rbx")


def read_csv(p):
    return list(csv.DictReader(open(p, encoding="utf-8", newline="")))


def run_stats(run_dir):
    """Per policy: total ms, queries, disagreeing queries, max F_hat (ms), skips, selector ms, discovery output rates."""
    ex, tm = read_csv(run_dir / "exactness.csv"), read_csv(run_dir / "timing_feasibility.csv")
    if len(ex) != len(tm):
        raise ValueError("%d exactness rows, %d timing rows" % (len(ex), len(tm)))
    out = {}
    for e, t in zip(ex, tm):
        p = out.setdefault(e["policy"], {"total": 0, "queries": 0, "bad": 0, "fhat": 0.0, "skips": 0, "selector_ms": 0.0,
                                         "rates": [], "rate": float(e.get("selector_rate") or 0)})
        p["total"] += int(t["seed_ms"] or 0) + int(t["ingest_ms"]) + int(t["query_ms"])
        p["queries"] += 1
        if sum(int(e[k] or 0) for k in ("missing", "extra", "utility_mismatch", "period_mismatch")):
            p["bad"] += 1
        p["fhat"] = max(p["fhat"], int(e.get("fhat_us") or 0) / 1000)
        p["skips"] += e["action"] == "bounded"
        p["selector_ms"] += int(e.get("selector_us") or 0) / 1000
        if e["action"] == "discover" and int(e.get("attempt_enum_ms") or 0) > 0:
            p["rates"].append(int(e["candidates"] or 0) / (int(e["attempt_enum_ms"]) / 1000))
    return out


def mean_se(xs):
    return st.mean(xs), (st.stdev(xs) / math.sqrt(len(xs)) if len(xs) > 1 else float("nan"))


def e2b_ratios(ledger, root):
    """bud3rb/F per (dataset, schedule) over the recorded E2B cells (for X7)."""
    out = defaultdict(list)
    for r in ledger:
        if not r["experiment"].startswith("E2B") or r["status"] != "OK":
            continue
        try:
            t = run_stats(root / r["run_dir"])
        except Exception:
            continue
        if "bud3rb" in t and "remine-nogc" in t:
            out[(r["dataset"], r["schedule"])].append(t["bud3rb"]["total"] / t["remine-nogc"]["total"])
    return out


def summarize(cfg, root, write_repeats=False, config_path=None):
    out = root / cfg["out"]
    ledger = list(csv.DictReader(open(out / "cells.tsv", encoding="utf-8", newline=""), delimiter="\t")) \
        if (out / "cells.tsv").exists() else []
    problems, failures, lines = [], [], []
    groups = defaultdict(list)
    x1_n = x1_bad = x2_n = 0
    rates, rate_cap = [], float(cfg.get("selector_rate", 0))
    sel_runs = sel_bad = 0
    for r in ledger:
        if not r["experiment"].startswith("E2X"):
            continue
        if r["status"] != "OK":
            problems.append("%s: status %s" % (r["key"], r["status"]))
            continue
        d = root / r["run_dir"]
        order = r["policies"].split(",")
        try:
            t = run_stats(d)
            for name, rows in check_reserve.read_run(d).items():
                k, pr, _ = check_reserve.check_policy(name, rows)
                x2_n += k
                failures.extend("X2 " + x for x in pr)
        except Exception as e:
            problems.append("%s: cannot read %s (%s)" % (r["key"], d.as_posix(), e))
            continue
        if sorted(t) != sorted(POLICIES):
            problems.append("%s: policies %s, expected %s" % (r["key"], sorted(t), sorted(POLICIES)))
            continue
        for pol in order[1:]:
            x1_n += t[pol]["queries"]
            x1_bad += t[pol]["bad"]
        for pol in ("bud3rb", "bud3rbx"):
            rates += t[pol]["rates"]
        if t["bud3rbx"]["rate"] != rate_cap:
            failures.append("X0 %s: bud3rbx ran with selector rate %s, configuration says %s" % (r["key"], t["bud3rbx"]["rate"], rate_cap))
        sel_runs += 1
        if t["bud3rbx"]["selector_ms"] > 0.02 * t["remine-nogc"]["total"]:
            sel_bad += 1
            failures.append("X4 %s: selector %.0f ms > 2%% of F %d ms" % (r["key"], t["bud3rbx"]["selector_ms"], t["remine-nogc"]["total"]))
        groups[(r["dataset"], r["schedule"])].append((order, t))
    if x1_bad:
        failures.append("X1: %d of %d queries disagree with the first policy of their JVM" % (x1_bad, x1_n))
    over = [x for x in rates if x > rate_cap]
    if over:
        failures.append("X0: %d of %d completed discoveries above the selector rate %.0f (largest %.0f)" % (len(over), len(rates), rate_cap, max(over)))
    cal = root / cfg.get("calibration_file", "results/9950x/selector_rate.txt")
    cal_rate = None
    if cal.exists():
        for line in cal.read_text(encoding="utf-8").splitlines():
            if line.startswith("rate="):
                cal_rate = float(line[5:])
        if cal_rate is not None and cal_rate > rate_cap:
            failures.append("X0: calibration rate %.0f above the selector rate %.0f" % (cal_rate, rate_cap))
    e2b = e2b_ratios(ledger, root)
    counts = defaultdict(lambda: [0, 0])
    near = []
    for key in sorted(groups):
        runs = groups[key]
        ratio = {p: [t[p]["total"] / t["remine-nogc"]["total"] for _, t in runs] for p in POLICIES[1:]}
        m = {p: st.mean(v) for p, v in ratio.items()}
        skips = [t["bud3rbx"]["skips"] for _, t in runs]
        verdicts = []
        ok6 = all(t["bud3rbx"]["total"] <= 1.15 * (1.10 * t["remine-nogc"]["total"] + t["bud3rbx"]["fhat"]) + 50 * t["bud3rbx"]["queries"]
                  for _, t in runs)
        counts["X6"][0] += 1; counts["X6"][1] += ok6
        verdicts.append("X6 %s" % ("ok" if ok6 else "FAIL"))
        if not ok6:
            failures.append("X6 %s %s" % key)
        if all(s == 0 for s in skips):
            ok = m["bud3rbx"] <= m["bud3rb"] + 0.02
            counts["X4"][0] += 1; counts["X4"][1] += ok
            verdicts.append("X4 %s" % ("ok" if ok else "FAIL"))
            if not ok:
                failures.append("X4 %s %s: bud3rbx/F %.3f > bud3rb/F %.3f + 0.02" % (key + (m["bud3rbx"], m["bud3rb"])))
        elif all(s > 0 for s in skips):
            ok = m["bud3rbx"] <= m["bud3rb"]
            counts["X5"][0] += 1; counts["X5"][1] += ok
            verdicts.append("X5 %s" % ("ok" if ok else "FAIL"))
            if not ok:
                failures.append("X5 %s %s: bud3rbx/F %.3f > bud3rb/F %.3f" % (key + (m["bud3rbx"], m["bud3rb"])))
        else:
            verdicts.append("mixed skips")
        x7 = "-"
        if key in e2b and len(e2b[key]) and len(ratio["bud3rb"]):
            a, b = ratio["bud3rb"], e2b[key]
            se = math.sqrt((st.variance(a) / len(a) if len(a) > 1 else 0) + (st.variance(b) / len(b) if len(b) > 1 else 0))
            x7 = "%+.3f (2se %.3f)" % (st.mean(a) - st.mean(b), 2 * se)
        rx = st.mean(t["bud3rbx"]["total"] for _, t in runs)
        if len(runs) < 3 and any(abs(1 - rx / st.mean(t[p]["total"] for _, t in runs)) < 0.10 for p in ("bud3rb", "remine-nogc")):
            near.append(list(key))
        lines.append([key[0], key[1], len(runs), "%.0f" % st.mean(t["remine-nogc"]["total"] for _, t in runs),
                      "%.3f" % m["bud3rb"], "%.3f" % m["bud3rbx"], "/".join(str(s) for s in skips), x7, "; ".join(verdicts)])
    head = ["dataset", "schedule", "runs", "F_ms", "bud3rb_over_F", "bud3rbx_over_F", "skips_per_run", "x7_bud3rb_vs_E2B", "checks"]
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "summary_E2X.tsv", "w", encoding="utf-8", newline="\n") as f:
        for l in [head] + lines:
            f.write("\t".join(str(x) for x in l) + "\n")
    for l in [head] + lines:
        print("  ".join(str(x) for x in l))
    print("X0: %d completed discoveries, largest output rate %s, selector rate %.0f; calibration rate %s" % (
        len(rates), "%.0f" % max(rates) if rates else "-", rate_cap, "%.0f" % cal_rate if cal_rate is not None else "absent"))
    print("X1: %d queries of the policies after the first compared with the first, %d disagreeing" % (x1_n, x1_bad))
    print("X2: %d bud3rb and bud3rbx queries checked against the reserve and selector rules" % x2_n)
    print("X4: %d of %d pairs as predicted; selector time above 2%% of F in %d of %d runs" % (counts["X4"][1], counts["X4"][0], sel_bad, sel_runs))
    for k in ("X5", "X6"):
        print("%s: %d of %d pairs as predicted" % (k, counts[k][1], counts[k][0]))
    for k, n in (("X1", x1_n), ("X2", x2_n), ("X6", counts["X6"][0]), ("X0", len(rates))):
        if n == 0:
            failures.append("%s: nothing compared" % k)
    print("pairs needing repeats: %d %s" % (len(near), near))
    if write_repeats and near:
        base = cfg["experiments"]["E2X"]
        cfg["experiments"]["E2XR"] = {"about": "generated by tools/summarize_selector.py: pairs near a tie with fewer than three runs",
                                      "pairs": near, "policies": base["policies"], "oracle": base["oracle"], "rotate": True,
                                      "first_rep": 1, "reps": 2, "limit_s": base["limit_s"], "props": base.get("props", [])}
        with open(config_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(cfg, indent=2) + "\n")
        print("-> experiment E2XR written to %s" % Path(config_path).as_posix())
    for p in problems:
        print("PROBLEM " + p)
    for x in failures:
        print("FAILED " + x)
    return 1 if failures or problems else 0


def self_test():
    """A fixture of four pairs (with and without skips), then injected defects; each must produce its outcome."""
    def rows_for(totals, order, skips, bad=0, rate=1e8, sel_ms=1, disc_rate=None):
        ex, tm = [], []
        for pol in order:
            for q in range(3):
                a = ""
                if pol in ("bud3rb", "bud3rbx"):
                    a = "seed" if q == 0 else ("bounded" if (pol == "bud3rbx" and q <= skips) else "discover")
                e = {"policy": pol, "missing": str(bad if (q == 2 and pol == order[-1]) else 0), "extra": "0", "utility_mismatch": "0",
                     "period_mismatch": "0", "query": str(q), "action": a, "fhat_us": "", "charge_us": "", "reserve_us": "",
                     "gate": "", "attempt_budget_us": "", "floor_us": "", "budget_ms": "", "bound_log2": "", "selector_us": "",
                     "selector_rate": "", "candidates": "0", "attempt_enum_ms": ""}
                if pol in ("bud3rb", "bud3rbx") and q > 0:
                    e.update(fhat_us="100000", gate="0")
                    if a == "discover":
                        e.update(charge_us="10000", attempt_budget_us="100000", budget_ms="100", floor_us="0",
                                 candidates=str(int((disc_rate or 1000) * 0.01)), attempt_enum_ms="10")
                if pol in ("bud3rb", "bud3rbx"):
                    # reserve replay: advance 1e5 at the first attempt, each completion adds 9e4, each query q >= 1 adds 1e4
                    res = 0
                    opened = False
                    for qq in range(1, q + 1):
                        act = "bounded" if (pol == "bud3rbx" and qq <= skips) else "discover"
                        if act == "discover":
                            if not opened:
                                res += 100000; opened = True
                            res += 90000
                        res += 10000
                    e["reserve_us"] = str(res)
                if pol == "bud3rbx":
                    e["selector_rate"] = "%.1f" % rate
                    e["selector_us"] = str(sel_ms * 1000)
                    if q > 0:
                        e["bound_log2"] = "100.000000000" if a == "bounded" else "1.000000000"
                ex.append(e)
                tm.append({"seed_ms": "", "ingest_ms": "0", "query_ms": str(totals[pol] // 3)})
        return ex, tm

    def build(tmp, cells):
        out = Path(tmp) / "results" / "x"
        out.mkdir(parents=True)
        head = ["key", "experiment", "dataset", "schedule", "policies", "threads", "rep", "status", "exit_code", "run_dir"]
        rows = []
        for i, (exp, ds, sch, rep, totals, skips, kw) in enumerate(cells):
            k = rep % 3
            order = list(POLICIES[k:] + POLICIES[:k])
            d = out / ("run%d" % i)
            d.mkdir()
            ex, tm = rows_for(totals, order, skips, **kw)
            for name, data in (("exactness.csv", ex), ("timing_feasibility.csv", tm)):
                with open(d / name, "w", encoding="utf-8", newline="") as f:
                    w = csv.DictWriter(f, fieldnames=list(data[0].keys()))
                    w.writeheader()
                    w.writerows(data)
            rows.append(["%s|%s|%s|r%d" % (exp, ds, sch, rep), exp, ds, sch, ",".join(order), "16", str(rep), "OK", "0",
                         "results/x/run%d" % i])
        with open(out / "cells.tsv", "w", encoding="utf-8", newline="") as f:
            f.write("\t".join(head) + "\n" + "\n".join("\t".join(r) for r in rows) + "\n")

    cfg = {"out": "results/x", "selector_rate": 1e8, "calibration_file": "results/x/none.txt",
           "experiments": {"E2X": {"policies": list(POLICIES), "oracle": "first", "limit_s": 60}}}
    tot = lambda f, rb, rx: {"remine-nogc": f, "bud3rb": rb, "bud3rbx": rx}
    clean = [("E2X", "A", "S1", r, tot(30000, 30000, 30300), 0, {}) for r in range(3)] + \
            [("E2X", "B", "S1", r, tot(30000, 45000, 30600), 1, {}) for r in range(3)] + \
            [("E2X", "C", "S2", 0, tot(30000, 26000, 20000), 0, {})]
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

    case("clean fixture", clean, 0, ("X4: 2 of 2", "X5: 1 of 1", "X6: 3 of 3", "pairs needing repeats: 0"))
    bad = list(clean); bad[1] = ("E2X", "A", "S1", 1, tot(30000, 30000, 30300), 0, {"bad": 1})
    case("defect: one query disagrees with the first policy (X1)", bad, 1, ("FAILED X1",))
    bad = [("E2X", "A", "S1", r, tot(30000, 30000, 33000), 0, {}) for r in range(3)] + clean[3:]
    case("defect: bud3rbx slower than bud3rb + 0.02 without a skip (X4)", bad, 1, ("FAILED X4",))
    bad = list(clean); bad[0] = ("E2X", "A", "S1", 0, tot(30000, 30000, 30300), 0, {"sel_ms": 1000})
    case("defect: selector time above 2% of F (X4)", bad, 1, ("FAILED X4",))
    bad = clean[:3] + [("E2X", "B", "S1", r, tot(30000, 45000, 46000), 1, {}) for r in range(3)] + clean[6:]
    case("defect: bud3rbx slower than bud3rb with skips (X5)", bad, 1, ("FAILED X5",))
    bad = list(clean); bad[6] = ("E2X", "C", "S2", 0, tot(30000, 26000, 90000), 0, {})
    case("defect: bud3rbx above the X6 bound", bad, 1, ("FAILED X6",))
    bad = list(clean); bad[3] = ("E2X", "B", "S1", 0, tot(30000, 45000, 30600), 1, {"disc_rate": 2e8})
    case("defect: a discovery faster than the selector rate (X0)", bad, 1, ("FAILED X0",))
    bad = list(clean); bad[3] = ("E2X", "B", "S1", 0, tot(30000, 45000, 30600), 1, {"rate": 1.0})
    case("defect: bud3rbx ran with another selector rate (X0)", bad, 1, ("FAILED X0",))
    near = [("E2X", "C", "S2", 0, tot(30000, 30000, 29500), 0, {})]
    case("near tie with one run: repeat generated", clean[:6] + near, 0, ("pairs needing repeats: 1 [['C', 'S2']]",))
    case("vacuous: empty ledger is not a pass", [], 1, ("nothing compared",))
    for name, ok, detail in cases:
        print("%-66s %s  %s" % (name, "ok" if ok else "FAILED", detail))
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
