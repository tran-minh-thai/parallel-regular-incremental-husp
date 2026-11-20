#!/usr/bin/env python3
"""Summarise the window-gate experiment (E2W): F (remine-nogc), bud3rb, bud3rbx and bud3rbw in one JVM per cell.

    python3 tools/summarize_gate.py [--config experiments/campaign_9950x_gate.json]
    python3 tools/summarize_gate.py --self-test

Reads <out>/cells.tsv and every recorded cell of the experiments whose name starts with E2W. Per (dataset, schedule) and
policy: total cost (seed + ingest + query, milliseconds) over F of the same run, averaged over the runs. Checks, each
printed with its denominator:

  W1  every query of the policies after the first agrees with the first;
  W2  tools/check_reserve.py on every bud3rb, bud3rbx and bud3rbw run: reserve replay, budgets, the selector's decision
      against its logged bound, and for bud3rbw the gate (closed exactly when increment_seqs >= 2 * selector_b, no bound
      when closed, selector_b never rising). The logged bound against an independent recomputation is checked on the
      development machine;
  W3  every bud3rbw run of the pairs not in gate_w3_excluded: sum selector_us <= 2% of F (the excluded pairs are printed);
  W4  pairs where bud3rbw never skips in any run: bud3rbw/F <= bud3rb/F + 0.02;
  W5  every pair: bud3rbw/F <= bud3rbx/F + max(0.02, 2 standard errors of the per-run difference); printed next to it, the
      share of F that bud3rbx spent in its selector at windows of at least 2B sequences (B from the dataset table);
  W6  every bud3rbw run: total <= 1.15 * (1.10 * F total + max F_hat) + 50 ms per query;
  W7  (descriptive) bud3rbx/F and bud3rb/F here against E2X, per pair: mean difference and two standard errors.

Output: <out>/summary_E2W.tsv and the printed report. Exit status 1 when a check fails, a cell cannot be read, or a check
had nothing to compare.
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

CONFIG = ROOT / "experiments" / "campaign_9950x_gate.json"
POLICIES = ("remine-nogc", "bud3rb", "bud3rbx", "bud3rbw")


def read_csv(p):
    return list(csv.DictReader(open(p, encoding="utf-8", newline="")))


def run_stats(run_dir, B):
    """Per policy: total ms, queries, disagreeing queries, max F_hat (ms), skips, selector ms, selector ms at n >= 2B."""
    ex, tm = read_csv(run_dir / "exactness.csv"), read_csv(run_dir / "timing_feasibility.csv")
    if len(ex) != len(tm):
        raise ValueError("%d exactness rows, %d timing rows" % (len(ex), len(tm)))
    out = {}
    for e, t in zip(ex, tm):
        p = out.setdefault(e["policy"], {"total": 0, "queries": 0, "bad": 0, "fhat": 0.0, "skips": 0, "sel": 0.0, "sel_long": 0.0})
        p["total"] += int(t["seed_ms"] or 0) + int(t["ingest_ms"]) + int(t["query_ms"])
        p["queries"] += 1
        if sum(int(e[k] or 0) for k in ("missing", "extra", "utility_mismatch", "period_mismatch")):
            p["bad"] += 1
        p["fhat"] = max(p["fhat"], int(e.get("fhat_us") or 0) / 1000)
        p["skips"] += e["action"] == "bounded"
        s = int(e.get("selector_us") or 0) / 1000
        p["sel"] += s
        if int(e.get("increment_seqs") or 0) >= 2 * B:
            p["sel_long"] += s
    return out


def two_se(xs):
    return 2 * st.stdev(xs) / math.sqrt(len(xs)) if len(xs) > 1 else float("nan")


def summarize(cfg, root):
    out = root / cfg["out"]
    ledger = list(csv.DictReader(open(out / "cells.tsv", encoding="utf-8", newline=""), delimiter="\t")) \
        if (out / "cells.tsv").exists() else []
    excluded = {tuple(x) for x in cfg.get("gate_w3_excluded", [])}
    problems, failures, lines = [], [], []
    groups, e2x = defaultdict(list), defaultdict(list)
    w1_n = w1_bad = w2_n = w3_runs = w3_bad = 0
    w3_excl = []
    for r in ledger:
        if not r["experiment"].startswith(("E2X", "E2W")) or r["dataset"] not in cfg["datasets"]:
            continue                                  # other experiments share the ledger; E2X is read for W7 only
        key = (r["dataset"], r["schedule"])
        B = int(cfg["datasets"][r["dataset"]]["B"])
        if r["experiment"].startswith("E2X") and r["status"] == "OK":
            try:
                t = run_stats(root / r["run_dir"], B)
                e2x[key].append(t)
            except Exception:
                pass
            continue
        if not r["experiment"].startswith("E2W"):
            continue
        if r["status"] != "OK":
            problems.append("%s: status %s" % (r["key"], r["status"]))
            continue
        d = root / r["run_dir"]
        order = r["policies"].split(",")
        try:
            t = run_stats(d, B)
            for name, rows in check_reserve.read_run(d).items():
                k, pr, _ = check_reserve.check_policy(name, rows)
                w2_n += k
                failures.extend("W2 " + x for x in pr)
        except Exception as e:
            problems.append("%s: cannot read %s (%s)" % (r["key"], d.as_posix(), e))
            continue
        if sorted(t) != sorted(POLICIES):
            problems.append("%s: policies %s, expected %s" % (r["key"], sorted(t), sorted(POLICIES)))
            continue
        for pol in order[1:]:
            w1_n += t[pol]["queries"]
            w1_bad += t[pol]["bad"]
        F = t["remine-nogc"]["total"]
        share = 100.0 * t["bud3rbw"]["sel"] / F
        if key in excluded:
            w3_excl.append("%s %s %.1f%%" % (key[0], key[1], share))
        else:
            w3_runs += 1
            if t["bud3rbw"]["sel"] > 0.02 * F:
                w3_bad += 1
                failures.append("W3 %s: selector %.0f ms > 2%% of F %d ms" % (r["key"], t["bud3rbw"]["sel"], F))
        groups[key].append(t)
    if w1_bad:
        failures.append("W1: %d of %d queries disagree with the first policy of their JVM" % (w1_bad, w1_n))
    counts = defaultdict(lambda: [0, 0])
    for key in sorted(groups):
        runs = groups[key]
        ratio = {p: [t[p]["total"] / t["remine-nogc"]["total"] for t in runs] for p in POLICIES[1:]}
        m = {p: st.mean(v) for p, v in ratio.items()}
        verdicts = []
        ok6 = all(t["bud3rbw"]["total"] <= 1.15 * (1.10 * t["remine-nogc"]["total"] + t["bud3rbw"]["fhat"]) + 50 * t["bud3rbw"]["queries"]
                  for t in runs)
        counts["W6"][0] += 1; counts["W6"][1] += ok6
        verdicts.append("W6 %s" % ("ok" if ok6 else "FAIL"))
        if not ok6:
            failures.append("W6 %s %s" % key)
        skips = [t["bud3rbw"]["skips"] for t in runs]
        if all(s == 0 for s in skips):
            ok4 = m["bud3rbw"] <= m["bud3rb"] + 0.02
            counts["W4"][0] += 1; counts["W4"][1] += ok4
            verdicts.append("W4 %s" % ("ok" if ok4 else "FAIL"))
            if not ok4:
                failures.append("W4 %s %s: bud3rbw/F %.3f > bud3rb/F %.3f + 0.02" % (key + (m["bud3rbw"], m["bud3rb"])))
        diff = [w - x for w, x in zip(ratio["bud3rbw"], ratio["bud3rbx"])]
        tol = max(0.02, two_se(diff)) if len(diff) > 1 else 0.02
        ok5 = st.mean(diff) <= tol
        counts["W5"][0] += 1; counts["W5"][1] += ok5
        verdicts.append("W5 %s" % ("ok" if ok5 else "FAIL"))
        if not ok5:
            failures.append("W5 %s %s: bud3rbw/F - bud3rbx/F = %+.3f > %.3f" % (key + (st.mean(diff), tol)))
        long_share = st.mean(t["bud3rbx"]["sel_long"] / t["remine-nogc"]["total"] for t in runs)
        w7 = []
        for p in ("bud3rbx", "bud3rb"):
            old = [t[p]["total"] / t["remine-nogc"]["total"] for t in e2x.get(key, []) if p in t]
            if old:
                se = math.sqrt((st.variance(ratio[p]) / len(ratio[p]) if len(ratio[p]) > 1 else 0) + (st.variance(old) / len(old) if len(old) > 1 else 0))
                w7.append("%s %+.3f (2se %.3f)" % (p, m[p] - st.mean(old), 2 * se))
        lines.append([key[0], key[1], len(runs), "%.0f" % st.mean(t["remine-nogc"]["total"] for t in runs),
                      "%.3f" % m["bud3rb"], "%.3f" % m["bud3rbx"], "%.3f" % m["bud3rbw"], "%+.3f" % st.mean(diff), "%.3f" % tol,
                      "%.3f" % long_share, "/".join(str(s) for s in skips), "; ".join(w7) or "-", "; ".join(verdicts)])
    head = ["dataset", "schedule", "runs", "F_ms", "bud3rb_over_F", "bud3rbx_over_F", "bud3rbw_over_F", "w_minus_x", "w5_tol",
            "bud3rbx_selector_long_over_F", "skips_per_run", "w7_vs_E2X", "checks"]
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "summary_E2W.tsv", "w", encoding="utf-8", newline="\n") as f:
        for l in [head] + lines:
            f.write("\t".join(str(x) for x in l) + "\n")
    for l in [head] + lines:
        print("  ".join(str(x) for x in l))
    print("W1: %d queries of the policies after the first compared with the first, %d disagreeing" % (w1_n, w1_bad))
    print("W2: %d bud3rb, bud3rbx and bud3rbw queries checked against the reserve, selector and gate rules" % w2_n)
    print("W3: selector time above 2%% of F in %d of %d runs; excluded pairs, per run: %s" % (w3_bad, w3_runs, ", ".join(w3_excl) or "none"))
    for k in ("W4", "W5", "W6"):
        print("%s: %d of %d pairs as predicted" % (k, counts[k][1], counts[k][0]))
    for k, n in (("W1", w1_n), ("W2", w2_n), ("W3", w3_runs), ("W5", counts["W5"][0])):
        if n == 0:
            failures.append("%s: nothing compared" % k)
    for p in problems:
        print("PROBLEM " + p)
    for x in failures:
        print("FAILED " + x)
    return 1 if failures or problems else 0


def self_test():
    """A fixture of two pairs, then injected defects; each must produce its outcome."""
    def rows_for(totals, order, skips, gated, bad=0, sel_ms=1):
        ex, tm = [], []
        for pol in order:
            sel = pol in ("bud3rbx", "bud3rbw")
            for q in range(3):
                a = ""
                if pol != "remine-nogc":
                    a = "seed" if q == 0 else ("bounded" if (sel and q <= skips) else "discover")
                gate = pol == "bud3rbw" and q > 0 and gated and a == "discover"
                e = {"policy": pol, "missing": str(bad if (q == 2 and pol == order[-1]) else 0), "extra": "0", "utility_mismatch": "0",
                     "period_mismatch": "0", "query": str(q), "action": a, "fhat_us": "", "charge_us": "", "reserve_us": "",
                     "gate": "", "attempt_budget_us": "", "floor_us": "", "budget_ms": "", "bound_log2": "", "selector_us": "",
                     "selector_rate": "", "increment_seqs": str(5000 if gate else 10), "bound_gate": "window" if gate else "",
                     "selector_b": "1000" if pol == "bud3rbw" and q > 0 else ""}
                if pol != "remine-nogc" and q > 0:
                    e.update(fhat_us="100000", gate="0")
                    if a == "discover":
                        e.update(charge_us="10000", attempt_budget_us="100000", budget_ms="100", floor_us="0")
                if pol != "remine-nogc":
                    res, opened = 0, False
                    for qq in range(1, q + 1):
                        if not (sel and qq <= skips):
                            if not opened:
                                res += 100000; opened = True
                            res += 90000
                        res += 10000
                    e["reserve_us"] = str(res)
                if sel:
                    e["selector_rate"] = "100000000.0"
                    e["selector_us"] = str(sel_ms * 1000 if pol == "bud3rbw" else 1000)
                    if q > 0 and not gate:
                        e["bound_log2"] = "100.000000000" if a == "bounded" else "1.000000000"
                ex.append(e)
                tm.append({"seed_ms": "", "ingest_ms": "0", "query_ms": str(totals[pol] // 3)})
        return ex, tm

    def build(tmp, cells):
        out = Path(tmp) / "results" / "x"
        out.mkdir(parents=True)
        head = ["key", "experiment", "dataset", "schedule", "policies", "threads", "rep", "status", "exit_code", "run_dir"]
        rows = []
        for i, (exp, ds, sch, rep, totals, skips, gated, kw) in enumerate(cells):
            k = rep % 4
            order = list(POLICIES[k:] + POLICIES[:k])
            d = out / ("run%d" % i)
            d.mkdir()
            ex, tm = rows_for(totals, order, skips, gated, **kw)
            for name, data in (("exactness.csv", ex), ("timing_feasibility.csv", tm)):
                with open(d / name, "w", encoding="utf-8", newline="") as f:
                    w = csv.DictWriter(f, fieldnames=list(data[0].keys()))
                    w.writeheader()
                    w.writerows(data)
            rows.append(["%s|%s|%s|r%d" % (exp, ds, sch, rep), exp, ds, sch, ",".join(order), "16", str(rep), "OK", "0",
                         "results/x/run%d" % i])
        with open(out / "cells.tsv", "w", encoding="utf-8", newline="") as f:
            f.write("\t".join(head) + "\n" + "\n".join("\t".join(r) for r in rows) + "\n")

    cfg = {"out": "results/x", "datasets": {"A": {"B": 1000}, "B": {"B": 1000}}, "gate_w3_excluded": [["B", "S1"]]}
    tot = lambda f, rb, rx, rw: {"remine-nogc": f, "bud3rb": rb, "bud3rbx": rx, "bud3rbw": rw}
    clean = [("E2W", "A", "S1", r, tot(30000, 30000, 30300, 30100), 0, True, {}) for r in range(4)] + \
            [("E2W", "B", "S1", r, tot(30000, 45000, 30600, 30500), 1, False, {}) for r in range(4)]
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

    case("clean fixture", clean, 0, ("W4: 1 of 1", "W5: 2 of 2", "W6: 2 of 2", "W3: selector time above 2% of F in 0 of 4"))
    bad = list(clean); bad[1] = ("E2W", "A", "S1", 1, tot(30000, 30000, 30300, 30100), 0, True, {"bad": 1})
    case("defect: one query disagrees with the first policy (W1)", bad, 1, ("FAILED W1",))
    bad = [("E2W", "A", "S1", r, tot(30000, 30000, 30300, 33000), 0, True, {}) for r in range(4)] + clean[4:]
    case("defect: bud3rbw slower than bud3rbx and bud3rb (W4, W5)", bad, 1, ("FAILED W4", "FAILED W5"))
    bad = list(clean); bad[0] = ("E2W", "A", "S1", 0, tot(30000, 30000, 30300, 30100), 0, True, {"sel_ms": 1000})
    case("defect: bud3rbw selector above 2% of F (W3)", bad, 1, ("FAILED W3",))
    bad = list(clean); bad[4] = ("E2W", "B", "S1", 0, tot(30000, 45000, 30600, 30500), 1, False, {"sel_ms": 1000})
    case("excluded pair above 2% is printed, not failed (W3)", bad, 0, ("B S1 10.0%",))   # 3 queries x 1000 ms of 30000 ms
    bad = list(clean); bad[2] = ("E2W", "A", "S1", 2, tot(30000, 30000, 30300, 90000), 0, True, {})
    case("defect: bud3rbw above the W6 bound", bad, 1, ("FAILED W6",))
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
