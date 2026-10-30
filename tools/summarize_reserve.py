#!/usr/bin/env python3
"""Summarise the reserve-gate experiment (E2S) and generate the repeats it needs.

    python3 tools/summarize_reserve.py [--config experiments/campaign_9950x_reserve.json] [--write-repeats]
    python3 tools/summarize_reserve.py --self-test

Reads <out>/cells.tsv of the configuration and every recorded cell of the experiments whose name starts with E2S
(the order check E2SO is reported apart). Per (dataset, schedule) and policy: the total cost (seed + ingest + query,
milliseconds, mean over repetitions) and the ratios to F (remine-nogc, the in-run reference) and of bud3rs to bud3fs.
Checks, each printed with its denominator:

  D1  every query of bud3fs and bud3rs* agrees with F of the same run;
  D2  tools/check_reserve.py on every run: logged reserve equals the recomputation, and the cost bound holds;
  D3  bud3rs (eps 0.10, c 1): total <= 1.15 * ((1 + eps) * F total + c * max F_hat) + 50 ms per query;
  D4  each pair of the configuration's "d4_pairs": bud3rs/F < bud3fs/F by more than three times the larger
      coefficient of variation of the two ratios over the repetitions; each "d4_tie" pair: within that margin;
  D5  each pair of "d5_pairs": bud3rs/F <= bud3fs/F + 0.05.

--write-repeats stores experiment E2SR (two more repetitions) for every pair with fewer than three runs whose
bud3rs/bud3fs or bud3rs/F lies within 10% of 1, or that D4 names (D4 needs a coefficient of variation).
Output: <out>/summary_E2S.tsv and the printed report. Exit status 1 when a check fails, a cell cannot be read, or a
check had nothing to compare.
"""
import argparse
import csv
import json
import statistics as st
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import check_reserve  # noqa: E402

CONFIG = ROOT / "experiments" / "campaign_9950x_reserve.json"


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def run_totals(run_dir):
    """Per policy: total ms, query count, disagreeing queries, max F_hat (ms)."""
    ex, tm = read_csv(run_dir / "exactness.csv"), read_csv(run_dir / "timing_feasibility.csv")
    if len(ex) != len(tm):
        raise ValueError("%d exactness rows, %d timing rows" % (len(ex), len(tm)))
    out = {}
    for e, t in zip(ex, tm):
        p = out.setdefault(e["policy"], {"total": int(t["seed_ms"] or 0), "queries": 0, "bad": 0, "fhat": 0})
        p["total"] += int(t["ingest_ms"]) + int(t["query_ms"])
        p["queries"] += 1
        if sum(int(e[k] or 0) for k in ("missing", "extra", "utility_mismatch", "period_mismatch")):
            p["bad"] += 1
        p["fhat"] = max(p["fhat"], int(e.get("fhat_us") or 0) / 1000)
    return out


def cv(xs):
    return st.stdev(xs) / st.mean(xs) if len(xs) > 1 and st.mean(xs) else float("nan")


def summarize(cfg, root, write_repeats=False, config_path=None):
    out = root / cfg["out"]
    ledger = [r for r in csv.DictReader(open(out / "cells.tsv", encoding="utf-8", newline=""), delimiter="\t")] \
        if (out / "cells.tsv").exists() else []
    problems, failures, lines = [], [], []
    groups, order_check = defaultdict(list), defaultdict(list)
    d1_n = d1_bad = d2_n = 0
    for r in ledger:
        if not r["experiment"].startswith("E2S"):
            continue
        if r["status"] != "OK":
            problems.append("%s: status %s" % (r["key"], r["status"]))
            continue
        d = root / r["run_dir"]
        try:
            t = run_totals(d)
            n, p, _ = (0, [], "")
            for name, rows in check_reserve.read_run(d).items():
                k, pr, _ = check_reserve.check_policy(name, rows)
                n += k
                p += pr
        except Exception as e:
            problems.append("%s: cannot read %s (%s)" % (r["key"], d.as_posix(), e))
            continue
        d2_n += n
        failures += ["D2 " + x for x in p]
        for pol, v in t.items():
            if pol != "remine-nogc":
                d1_n += v["queries"]
                d1_bad += v["bad"]
        (order_check if r["experiment"] == "E2SO" else groups)[(r["dataset"], r["schedule"])].append(t)
    if d1_bad:
        failures.append("D1: %d of %d queries disagree with F" % (d1_bad, d1_n))
    d4 = {tuple(p) for p in cfg.get("d4_pairs", [])}
    d4tie = {tuple(p) for p in cfg.get("d4_tie", [])}
    d5 = {tuple(p) for p in cfg.get("d5_pairs", [])}
    counts = defaultdict(lambda: [0, 0])
    near = []
    for key in sorted(groups):
        runs = groups[key]
        if any("bud3rs" not in t or "bud3fs" not in t or "remine-nogc" not in t for t in runs):
            problems.append("%s %s: a run lacks F, bud3fs or bud3rs" % key)
            continue
        F = [t["remine-nogc"]["total"] for t in runs]
        fs = [t["bud3fs"]["total"] / f for t, f in zip(runs, F)]
        rs = [t["bud3rs"]["total"] / f for t, f in zip(runs, F)]
        rs_fs = st.mean(t["bud3rs"]["total"] for t in runs) / st.mean(t["bud3fs"]["total"] for t in runs)
        verdicts = []
        # D3, per run
        ok3 = True
        for t in runs:
            q = t["bud3rs"]["queries"]
            bound = 1.15 * (1.10 * t["remine-nogc"]["total"] + t["bud3rs"]["fhat"]) + 50 * q
            ok3 = ok3 and t["bud3rs"]["total"] <= bound
        counts["D3"][0] += 1
        counts["D3"][1] += ok3
        verdicts.append("D3 %s" % ("ok" if ok3 else "FAIL"))
        if not ok3:
            failures.append("D3 %s %s" % key)
        margin = 3 * max(cv(fs), cv(rs))
        if key in d4 or key in d4tie:
            if len(runs) < 2:
                verdicts.append("D4 not judged (1 run)")
                problems.append("D4 %s %s: one run, no coefficient of variation" % key)
            else:
                lower = st.mean(fs) - st.mean(rs)
                ok4 = lower > margin * st.mean(fs) if key in d4 else abs(lower) <= margin * st.mean(fs)
                counts["D4"][0] += 1
                counts["D4"][1] += ok4
                verdicts.append("D4%s %s" % (" tie" if key in d4tie else "", "ok" if ok4 else "FAIL"))
                if not ok4:
                    failures.append("D4 %s %s: bud3rs/F %.3f, bud3fs/F %.3f, margin %.3f" % (key + (st.mean(rs), st.mean(fs), margin)))
        if key in d5:
            ok5 = st.mean(rs) <= st.mean(fs) + 0.05
            counts["D5"][0] += 1
            counts["D5"][1] += ok5
            verdicts.append("D5 %s" % ("ok" if ok5 else "FAIL"))
            if not ok5:
                failures.append("D5 %s %s: bud3rs/F %.3f > bud3fs/F %.3f + 0.05" % (key + (st.mean(rs), st.mean(fs))))
        extra = {p: st.mean(t[p]["total"] for t in runs) / st.mean(F) for p in runs[0]
                 if p.startswith("bud3rs-") and all(p in t for t in runs)}
        if len(runs) < 3 and (abs(1 - rs_fs) < 0.10 or abs(1 - st.mean(rs)) < 0.10 or key in d4 or key in d4tie):
            near.append(list(key))
        lines.append([key[0], key[1], len(runs), "%.0f" % st.mean(F), "%.3f" % st.mean(fs), "%.3f" % st.mean(rs),
                      "%.3f" % rs_fs, "%.3f" % (cv(rs) if len(runs) > 1 else float("nan")),
                      " ".join("%s=%.3f" % kv for kv in sorted(extra.items())), "; ".join(verdicts)])
    for key in sorted(order_check):
        for t in order_check[key]:
            lines.append([key[0], key[1], "order check", "%d" % t["remine-nogc"]["total"],
                          "%.3f" % (t["bud3fs"]["total"] / t["remine-nogc"]["total"]),
                          "%.3f" % (t["bud3rs"]["total"] / t["remine-nogc"]["total"]), "", "", "", "reversed order"])
    head = ["dataset", "schedule", "runs", "F_ms", "bud3fs_over_F", "bud3rs_over_F", "bud3rs_over_bud3fs", "cv_bud3rs_over_F",
            "other_eps_over_F", "checks"]
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "summary_E2S.tsv", "w", encoding="utf-8", newline="\n") as f:
        for l in [head] + lines:
            f.write("\t".join(str(x) for x in l) + "\n")
    for l in [head] + lines:
        print("  ".join(str(x) for x in l))
    print("D1: %d queries of bud3fs and bud3rs compared with F of the same run, %d disagreeing" % (d1_n, d1_bad))
    print("D2: %d bud3rs queries checked against the reserve rule" % d2_n)
    for k in ("D3", "D4", "D5"):
        print("%s: %d of %d pairs as predicted" % (k, counts[k][1], counts[k][0]))
    for k, expect in (("D1", d1_n), ("D2", d2_n), ("D3", counts["D3"][0])):
        if expect == 0:
            failures.append("%s: nothing compared" % k)
    print("pairs needing repeats: %d %s" % (len(near), near))
    if write_repeats and near:
        base = cfg["experiments"]["E2S"]
        cfg["experiments"]["E2SR"] = {"about": "generated by tools/summarize_reserve.py: pairs near a tie or named by D4",
                                      "pairs": near, "policies": base["policies"], "oracle": base["oracle"],
                                      "first_rep": 1, "reps": 2, "limit_s": base["limit_s"], "props": base.get("props", [])}
        with open(config_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(cfg, indent=2) + "\n")
        print("-> experiment E2SR written to %s" % Path(config_path).as_posix())
    for p in problems:
        print("PROBLEM " + p)
    for x in failures:
        print("FAILED " + x)
    return 1 if failures or problems else 0


def self_test():
    """A fixture of three pairs, then injected defects; each must produce its outcome."""
    def cell_rows(policy_totals, bad=0, reserve_ok=True, fhat=100):
        ex, tm = [], []
        for pol, total in policy_totals.items():
            for q in range(2):
                e = {"policy": pol, "missing": str(bad if (q == 1 and pol != "remine-nogc") else 0), "extra": "0",
                     "utility_mismatch": "0", "period_mismatch": "0", "query": str(q), "action": "", "fhat_us": "",
                     "charge_us": "", "reserve_us": "", "gate": ""}
                t = {"seed_ms": "", "ingest_ms": "0", "query_ms": str(total // 2)}
                if pol.startswith("bud3rs"):
                    if q == 0:
                        e.update(action="seed", fhat_us="0", charge_us="0", reserve_us="0", gate="0")
                    else:
                        # one query skipped by the window backoff: the reserve gains the premium only
                        e.update(action="skipped", fhat_us=str(fhat * 1000), charge_us="0",
                                 reserve_us=str(int(0.1 * fhat * 1000) if reserve_ok else 7), gate="0")
                ex.append(e)
                tm.append(t)
        return ex, tm

    def build(tmp, pairs):
        out = Path(tmp) / "results" / "x"
        out.mkdir(parents=True)
        head = ["key", "experiment", "dataset", "schedule", "policies", "threads", "rep", "status", "exit_code", "run_dir"]
        rows = []
        for i, (exp, ds, sch, rep, totals, kw) in enumerate(pairs):
            d = out / ("run%d" % i)
            d.mkdir()
            ex, tm = cell_rows(totals, **kw)
            for name, data in (("exactness.csv", ex), ("timing_feasibility.csv", tm)):
                with open(d / name, "w", encoding="utf-8", newline="") as f:
                    w = csv.DictWriter(f, fieldnames=list(data[0].keys()))
                    w.writeheader()
                    w.writerows(data)
            rows.append(["%s|%s|%s|r%d" % (exp, ds, sch, rep), exp, ds, sch, "p", "16", str(rep), "OK", "0",
                         "results/x/run%d" % i])
        with open(out / "cells.tsv", "w", encoding="utf-8", newline="") as f:
            f.write("\t".join(head) + "\n" + "\n".join("\t".join(r) for r in rows) + "\n")
        return out

    cfg = {"out": "results/x", "d4_pairs": [["A", "S1"]], "d4_tie": [], "d5_pairs": [["B", "S1"]],
           "experiments": {"E2S": {"policies": ["remine-nogc", "bud3fs", "bud3rs"], "oracle": "first", "limit_s": 60}}}
    tot = lambda f, fs, rs: {"remine-nogc": f, "bud3fs": fs, "bud3rs": rs}
    clean = [("E2Sc", "A", "S1", r, tot(1000, 1500 + 10 * r, 1100 + 10 * r), {}) for r in range(3)] + \
            [("E2S", "B", "S1", 0, tot(10000, 4000, 4100), {})] + \
            [("E2S", "C", "S2", 0, tot(10000, 9800, 9900), {})]
    cases = []

    def case(name, pairs, expect_rc, must_print=()):
        with tempfile.TemporaryDirectory() as tmp:
            build(tmp, pairs)
            import contextlib
            import io
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                rc = summarize(json.loads(json.dumps(cfg)), Path(tmp))
            text = buf.getvalue()
        ok = rc == expect_rc and all(s in text for s in must_print)
        cases.append((name, ok, "exit %d" % rc))

    case("clean fixture", clean, 0, ("D4: 1 of 1", "D5: 1 of 1", "pairs needing repeats: 2 [['B', 'S1'], ['C', 'S2']]"))
    bad = list(clean)
    bad[0] = ("E2Sc", "A", "S1", 0, tot(1000, 1500, 1100), {"bad": 1})
    case("defect: one bud3rs query disagrees with F (D1)", bad, 1, ("FAILED D1",))
    bad = list(clean)
    bad[3] = ("E2S", "B", "S1", 0, tot(10000, 4000, 20000), {})
    case("defect: bud3rs above the D3 bound", bad, 1, ("FAILED D3",))
    bad = list(clean)
    bad[3] = ("E2S", "B", "S1", 0, tot(10000, 4000, 4000), {"reserve_ok": False})
    case("defect: logged reserve off the rule (D2)", bad, 1, ("FAILED D2",))
    bad = [("E2Sc", "A", "S1", r, tot(1000, 1100 + 10 * r, 1100 + 10 * r), {}) for r in range(3)] + clean[3:]
    case("defect: no gain where D4 predicts one", bad, 1, ("FAILED D4",))
    case("vacuous: empty ledger is not a pass", [], 1, ("nothing compared",))
    for name, ok, detail in cases:
        print("%-55s %s  %s" % (name, "ok" if ok else "FAILED", detail))
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
