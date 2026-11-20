#!/usr/bin/env python3
"""Summarise the seed clock experiment (E2C): F (remine-nogc), bud3rbw and its control bud3rbw-sgc in one JVM per cell.

    python3 tools/summarize_clock.py [--config experiments/campaign_9950x_clock.json]
    python3 tools/summarize_clock.py --self-test

bud3rbw-sgc keeps the collection (MemMeter.reset) inside the fused seed's clock; bud3rbw does not. Reads <out>/cells.tsv: the
cells of experiments E2C (three runs) and E2C-152 (one run), and for C8 the earlier cells of E2W and E2X. Checks, each printed
with its denominator:

  C1  every query of the policies after the first agrees with the first;
  C2  tools/check_reserve.py on every bud3rbw and bud3rbw-sgc run (reserve, selector, gate, and seed_gc: 0 on every row of
      bud3rbw, at least 1 at q0 of the control);
  C3  every run, query 0: engine_nodes of bud3rbw >= that of F, and equal to that of bud3rbw-sgc;
  C4  every three-run pair: mean over runs of (q0 query time of bud3rbw-sgc - of bud3rbw) >= 100 ms; pairs whose F takes more
      than 2 s at q0 are marked;
  C5  every three-run pair: median over runs of F_hat at q1 / F's q1 query time <= 1.30 for bud3rbw (the control's printed);
  C6  every three-run pair: bud3rbw/F <= bud3rbw-sgc/F + max(0.02, 2 standard errors of the per-run difference);
  C7  every run: bud3rbw total <= 1.15 * (1.10 * F total + max F_hat) + 50 ms per query (reported, no prediction);
  C8  (descriptive) bud3rbw-sgc/F here against bud3rbw/F of E2W and bud3rbx/F of E2X, per pair.

Output: <out>/summary_E2C.tsv and the printed report. Exit status 1 when a check fails, a cell cannot be read, or a check had
nothing to compare.
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

CONFIG = ROOT / "experiments" / "campaign_9950x_clock.json"
F, FIX, CTL = "remine-nogc", "bud3rbw", "bud3rbw-sgc"
POLICIES = (F, FIX, CTL)


def read_csv(p):
    return list(csv.DictReader(open(p, encoding="utf-8", newline="")))


def run_stats(run_dir):
    """Per policy: total ms, queries, disagreeing queries, max F_hat (ms), and the q0/q1 fields the checks need."""
    ex, tm = read_csv(run_dir / "exactness.csv"), read_csv(run_dir / "timing_feasibility.csv")
    if len(ex) != len(tm):
        raise ValueError("%d exactness rows, %d timing rows" % (len(ex), len(tm)))
    out = {}
    for e, t in zip(ex, tm):
        p = out.setdefault(e["policy"], {"total": 0, "queries": 0, "bad": 0, "fhat": 0.0, "q0_ms": None, "q0_nodes": None,
                                         "q1_ms": None, "q1_fhat": None})
        p["total"] += int(t["seed_ms"] or 0) + int(t["ingest_ms"]) + int(t["query_ms"])
        p["queries"] += 1
        if sum(int(e[k] or 0) for k in ("missing", "extra", "utility_mismatch", "period_mismatch")):
            p["bad"] += 1
        p["fhat"] = max(p["fhat"], int(e.get("fhat_us") or 0) / 1000)
        if e["query"] == "0":
            p["q0_ms"], p["q0_nodes"] = int(t["query_ms"]), int(e.get("engine_nodes") or -1)
        if e["query"] == "1":
            p["q1_ms"], p["q1_fhat"] = int(t["query_ms"]), (int(e["fhat_us"]) / 1000 if e.get("fhat_us") else None)
    return out


def two_se(xs):
    return 2 * st.stdev(xs) / math.sqrt(len(xs)) if len(xs) > 1 else float("nan")


def ratio(t, p):
    return t[p]["total"] / t[F]["total"]


def summarize(cfg, root):
    out = root / cfg["out"]
    ledger = list(csv.DictReader(open(out / "cells.tsv", encoding="utf-8", newline=""), delimiter="\t")) \
        if (out / "cells.tsv").exists() else []
    problems, failures, lines = [], [], []
    groups, single = defaultdict(list), defaultdict(list)
    old = {"E2W": defaultdict(list), "E2X": defaultdict(list)}
    c1_n = c1_bad = c2_n = c3_n = c3_bad = c7_runs = c7_bad = 0
    for r in ledger:
        key = (r["dataset"], r["schedule"])
        exp = r["experiment"]
        if exp in ("E2W", "E2X") and r["status"] == "OK":
            try:
                t = run_stats(root / r["run_dir"])
                pol = "bud3rbw" if exp == "E2W" else "bud3rbx"
                if pol in t and F in t:
                    old[exp][key].append(ratio(t, pol))
            except Exception:
                pass
            continue
        if exp not in ("E2C", "E2C-152"):
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
                c2_n += k
                failures.extend("C2 " + x for x in pr)
        except Exception as e:
            problems.append("%s: cannot read %s (%s)" % (r["key"], d.as_posix(), e))
            continue
        if sorted(t) != sorted(POLICIES):
            problems.append("%s: policies %s, expected %s" % (r["key"], sorted(t), sorted(POLICIES)))
            continue
        for pol in order[1:]:
            c1_n += t[pol]["queries"]
            c1_bad += t[pol]["bad"]
        c3_n += 1
        if not (t[FIX]["q0_nodes"] >= t[F]["q0_nodes"] >= 0 and t[FIX]["q0_nodes"] == t[CTL]["q0_nodes"]):
            c3_bad += 1
            failures.append("C3 %s: q0 nodes F %d, bud3rbw %d, control %d" % (r["key"], t[F]["q0_nodes"], t[FIX]["q0_nodes"], t[CTL]["q0_nodes"]))
        c7_runs += 1
        if t[FIX]["total"] > 1.15 * (1.10 * t[F]["total"] + t[FIX]["fhat"]) + 50 * t[FIX]["queries"]:
            c7_bad += 1
        (groups if exp == "E2C" else single)[key].append(t)
    if c1_bad:
        failures.append("C1: %d of %d queries disagree with the first policy of their JVM" % (c1_bad, c1_n))
    counts = defaultdict(lambda: [0, 0])
    for key in sorted(groups):
        runs = groups[key]
        verdicts = []
        dq0 = [t[CTL]["q0_ms"] - t[FIX]["q0_ms"] for t in runs]
        slow = st.mean(t[F]["q0_ms"] for t in runs) > 2000
        ok4 = st.mean(dq0) >= 100
        counts["C4"][0] += 1; counts["C4"][1] += ok4
        verdicts.append("C4 %s%s" % ("ok" if ok4 else "FAIL", " (F q0 > 2 s)" if slow else ""))
        if not ok4:
            failures.append("C4 %s %s: q0 difference %.0f ms < 100 ms%s" % (key + (st.mean(dq0), " (F q0 > 2 s)" if slow else "")))
        fh = [t[FIX]["q1_fhat"] / t[F]["q1_ms"] for t in runs if t[FIX]["q1_fhat"] and t[F]["q1_ms"]]
        fc = [t[CTL]["q1_fhat"] / t[F]["q1_ms"] for t in runs if t[CTL]["q1_fhat"] and t[F]["q1_ms"]]
        m5 = st.median(fh) if fh else float("nan")
        ok5 = bool(fh) and m5 <= 1.30
        counts["C5"][0] += 1; counts["C5"][1] += ok5
        verdicts.append("C5 %s" % ("ok" if ok5 else "FAIL"))
        if not ok5:
            failures.append("C5 %s %s: median F_hat1/F1 %.2f > 1.30" % (key + (m5,)))
        rf, rc = [ratio(t, FIX) for t in runs], [ratio(t, CTL) for t in runs]
        diff = [a - b for a, b in zip(rf, rc)]
        tol = max(0.02, two_se(diff)) if len(diff) > 1 else 0.02
        ok6 = st.mean(diff) <= tol
        counts["C6"][0] += 1; counts["C6"][1] += ok6
        verdicts.append("C6 %s" % ("ok" if ok6 else "FAIL"))
        if not ok6:
            failures.append("C6 %s %s: bud3rbw/F - control/F = %+.3f > %.3f" % (key + (st.mean(diff), tol)))
        c8 = []
        for exp, pol in (("E2W", "bud3rbw"), ("E2X", "bud3rbx")):
            o = old[exp].get(key, [])
            if o:
                se = math.sqrt((st.variance(rc) / len(rc) if len(rc) > 1 else 0) + (st.variance(o) / len(o) if len(o) > 1 else 0))
                c8.append("%s %s %+.3f (2se %.3f)" % (exp, pol, st.mean(rc) - st.mean(o), 2 * se))
        lines.append([key[0], key[1], len(runs), "%.0f" % st.mean(t[F]["total"] for t in runs), "%.3f" % st.mean(rf), "%.3f" % st.mean(rc),
                      "%+.3f" % st.mean(diff), "%.3f" % tol, "%.0f" % st.mean(dq0), "%.3f" % (st.mean(dq0) / st.mean(t[F]["total"] for t in runs)),
                      "%.2f" % m5, "%.2f" % (st.median(fc) if fc else float("nan")), "; ".join(c8) or "-", "; ".join(verdicts)])
    for key in sorted(single):
        t = single[key][0]
        lines.append([key[0], key[1], 1, "%d" % t[F]["total"], "%.3f" % ratio(t, FIX), "%.3f" % ratio(t, CTL), "-", "-",
                      "%d" % (t[CTL]["q0_ms"] - t[FIX]["q0_ms"]), "-", "-", "-", "-", "one run: C1, C2, C3, C7 only"])
    head = ["dataset", "schedule", "runs", "F_ms", "bud3rbw_over_F", "control_over_F", "fix_minus_control", "c6_tol", "q0_diff_ms",
            "q0_diff_over_F", "fhat1_over_F1", "control_fhat1_over_F1", "c8", "checks"]
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "summary_E2C.tsv", "w", encoding="utf-8", newline="\n") as f:
        for l in [head] + lines:
            f.write("\t".join(str(x) for x in l) + "\n")
    for l in [head] + lines:
        print("  ".join(str(x) for x in l))
    print("C1: %d queries of the policies after the first compared with the first, %d disagreeing" % (c1_n, c1_bad))
    print("C2: %d bud3rbw and bud3rbw-sgc queries checked against the reserve, selector, gate and seed_gc rules" % c2_n)
    print("C3: %d runs checked at q0, %d violating" % (c3_n, c3_bad))
    for k in ("C4", "C5", "C6"):
        print("%s: %d of %d pairs as predicted" % (k, counts[k][1], counts[k][0]))
    print("C7: bud3rbw above the bound in %d of %d runs (reported, no prediction)" % (c7_bad, c7_runs))
    for k, n in (("C1", c1_n), ("C2", c2_n), ("C3", c3_n), ("C4", counts["C4"][0])):
        if n == 0:
            failures.append("%s: nothing compared" % k)
    for p in problems:
        print("PROBLEM " + p)
    for x in failures:
        print("FAILED " + x)
    return 1 if failures or problems else 0


def self_test():
    """A fixture of two three-run pairs and one single-run pair, then injected defects; each must produce its outcome."""
    def rows_for(spec, order, bad=0):
        ex, tm = [], []
        for pol in order:
            s = spec[pol]
            for q in range(3):
                a = "" if pol == F else ("seed" if q == 0 else "discover")
                e = {"policy": pol, "missing": str(bad if (q == 2 and pol == order[-1]) else 0), "extra": "0", "utility_mismatch": "0",
                     "period_mismatch": "0", "query": str(q), "action": a, "fhat_us": "", "charge_us": "", "reserve_us": "", "gate": "",
                     "attempt_budget_us": "", "floor_us": "", "budget_ms": "", "bound_log2": "", "selector_us": "", "selector_rate": "",
                     "increment_seqs": "10", "bound_gate": "", "selector_b": "", "engine_nodes": str(s["nodes"] if q == 0 else 50),
                     "seed_gc": str(s.get("gc", 0) if q == 0 else 0)}
                if pol != F and q > 0:
                    e.update(fhat_us=str(s["fhat_us"]), gate="0", charge_us="10000", attempt_budget_us=str(s["fhat_us"]),
                             budget_ms=str(s["fhat_us"] // 1000), floor_us="0", selector_us="1000", selector_rate="100000000.0",
                             bound_log2="1.000000000", selector_b="1000")
                if pol != F:
                    res, opened = 0, False
                    for qq in range(1, q + 1):
                        if not opened:
                            res += s["fhat_us"]; opened = True
                        res += s["fhat_us"] - 10000
                        res += int(0.10 * s["fhat_us"])
                    e["reserve_us"] = str(res)
                    if q == 0: e["selector_us"] = "0"
                ex.append(e)
                tm.append({"seed_ms": "", "ingest_ms": "0", "query_ms": str(s["q0"] if q == 0 else s["qn"])})
        return ex, tm

    def build(tmp, cells):
        out = Path(tmp) / "results" / "x"
        out.mkdir(parents=True)
        head = ["key", "experiment", "dataset", "schedule", "policies", "threads", "rep", "status", "exit_code", "run_dir"]
        rows = []
        for i, (exp, ds, sch, rep, spec, kw) in enumerate(cells):
            k = rep % 3
            order = list(POLICIES[k:] + POLICIES[:k])
            d = out / ("run%d" % i)
            d.mkdir()
            ex, tm = rows_for(spec, order, **kw)
            for name, data in (("exactness.csv", ex), ("timing_feasibility.csv", tm)):
                with open(d / name, "w", encoding="utf-8", newline="") as f:
                    w = csv.DictWriter(f, fieldnames=list(data[0].keys()))
                    w.writeheader()
                    w.writerows(data)
            rows.append(["%s|%s|%s|r%d" % (exp, ds, sch, rep), exp, ds, sch, ",".join(order), "16", str(rep), "OK", "0", "results/x/run%d" % i])
        with open(out / "cells.tsv", "w", encoding="utf-8", newline="") as f:
            f.write("\t".join(head) + "\n" + "\n".join("\t".join(r) for r in rows) + "\n")

    cfg = {"out": "results/x"}

    def spec(f_q1=100, fix_fhat=110000, ctl_fhat=300000, fix_q0=110, ctl_q0=300, fix_nodes=120, ctl_nodes=120, ctl_gc=1, fix_qn=60, fix_gc=0):
        return {F: {"nodes": 100, "q0": 100, "qn": f_q1, "fhat_us": 0},
                FIX: {"nodes": fix_nodes, "q0": fix_q0, "qn": fix_qn, "fhat_us": fix_fhat, "gc": fix_gc},
                CTL: {"nodes": ctl_nodes, "q0": ctl_q0, "qn": 60, "fhat_us": ctl_fhat, "gc": ctl_gc}}
    clean = [("E2C", "A", "S1", r, spec(), {}) for r in range(3)] + [("E2C", "B", "S2", r, spec(), {}) for r in range(3)] + \
            [("E2C-152", "V", "S1", 0, spec(), {})]
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

    case("clean fixture", clean, 0, ("C3: 7 runs checked at q0, 0 violating", "C4: 2 of 2", "C5: 2 of 2", "C6: 2 of 2", "one run: C1"))
    bad = list(clean); bad[1] = ("E2C", "A", "S1", 1, spec(), {"bad": 1})
    case("defect: one query disagrees (C1)", bad, 1, ("FAILED C1",))
    bad = list(clean); bad[2] = ("E2C", "A", "S1", 2, spec(fix_gc=1), {})
    case("defect 19: a collection in bud3rbw's seed (C2)", bad, 1, ("FAILED C2",))
    bad = list(clean); bad[3] = ("E2C", "B", "S2", 0, spec(fix_nodes=90, ctl_nodes=90), {})
    case("defect: the seed explores fewer nodes than F (C3)", bad, 1, ("FAILED C3",))
    bad = list(clean); bad[4] = ("E2C", "B", "S2", 1, spec(ctl_nodes=121), {})
    case("defect: the control explores other nodes (C3)", bad, 1, ("FAILED C3",))
    bad = [("E2C", "A", "S1", r, spec(ctl_q0=150), {}) for r in range(3)] + clean[3:]
    case("defect: q0 difference below 100 ms (C4)", bad, 1, ("FAILED C4",))
    bad = [("E2C", "A", "S1", r, spec(fix_fhat=200000), {}) for r in range(3)] + clean[3:]
    case("defect: F_hat1 still inflated (C5)", bad, 1, ("FAILED C5",))
    bad = [("E2C", "A", "S1", r, spec(fix_qn=200), {}) for r in range(3)] + clean[3:]
    case("defect: bud3rbw slower than the control (C6)", bad, 1, ("FAILED C6",))
    case("vacuous: empty ledger is not a pass", [], 1, ("nothing compared",))
    for name, ok, detail in cases:
        print("%-66s %s  %s" % (name, "ok" if ok else "FAILED", detail))
    badc = [c for c in cases if not c[1]]
    print("self-test: %d/%d cases as expected" % (len(cases) - len(badc), len(cases)))
    return 1 if badc else 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default=str(CONFIG))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    cfg = json.loads(Path(args.config).read_text(encoding="utf-8"))
    return summarize(cfg, ROOT)


if __name__ == "__main__":
    sys.exit(main())
