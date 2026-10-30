#!/usr/bin/env python3
"""Check the heap guard experiment (E2G): bud3, whose guard reads the raw heap, against bud3live, whose guard
collects once on a breach and aborts only if the heap is still above it.

    python3 tools/summarize_heap_guard.py [--out results/9950x]            # E2G-raw / E2G-live cells of the ledger
    python3 tools/summarize_heap_guard.py --pair DATASET RAW_DIR LIVE_DIR   # two run directories (probes)
    python3 tools/summarize_heap_guard.py --self-test

For every dataset it pairs the run of F then bud3 with the run of F then bud3live and checks:

  G1  every bud3 abort is caused by the heap guard, and in at least 80% of them the heap after the
      collection that follows the abort is below the guard (the guard tripped on garbage);
  G2  bud3live returns exactly what F returns at every query;
  G3  on datasets where bud3 aborted at least once: bud3live aborts fewer times, its total cost
      (seed + ingest + query) is at most bud3's, and the cost bound P2 holds for it;
  G4  on datasets where bud3live's guard never collected: the two totals differ by at most 10%.

Every check prints its denominator; a check with nothing to compare is reported as not testable, never
as passed. Exit status is 1 when a check fails. Standard library only.
"""
import argparse
import csv
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import summarize_campaign as sc  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


def policy_runs(run_dir):
    """(F rows, bud rows, bud policy name) of one run directory."""
    by = sc.policy_rows(Path(run_dir))
    f = by.get("remine-nogc", [])
    bud = [k for k in by if k.startswith("bud3")]
    if len(bud) != 1 or not f:
        raise SystemExit("%s: expected remine-nogc and one bud3 policy, found %s" % (Path(run_dir).as_posix(), sorted(by)))
    return f, by[bud[0]], bud[0]


def aborts(rows):
    return [e for e, _ in rows if e.get("action") == "aborted"]


def mismatches(rows):
    return sum(1 for e, _ in rows if any(int(e.get(k) or 0) for k in ("missing", "extra", "utility_mismatch", "period_mismatch")))


def analyze(pairs):
    """pairs: [(dataset, raw_dir, live_dir)]. Returns (lines, failures, verdicts)."""
    lines, failures = [], []
    g1_n = g1_heap = g1_garbage = 0
    g2_q = g2_bad = 0
    g3 = []
    g4 = []
    for ds, raw_dir, live_dir in pairs:
        fr, br, bname = policy_runs(raw_dir)
        fl, bl, lname = policy_runs(live_dir)
        if bname != "bud3" or not lname.startswith("bud3live"):
            raise SystemExit("%s: raw run has %s and live run has %s" % (ds, bname, lname))
        a_raw, a_live = aborts(br), aborts(bl)
        for e in a_raw:
            g1_n += 1
            if e.get("abort_cause") == "heap":
                g1_heap += 1
                after, guard = int(e.get("heap_live_after_abort_mb") or -1), int(e.get("heap_guard_mb") or 0)
                if 0 <= after < guard:
                    g1_garbage += 1
        g2_q += len(bl)
        g2_bad += mismatches(bl)
        t_raw, t_live = sc.total_ms(br), sc.total_ms(bl)
        f_raw, f_live = sc.total_ms(fr), sc.total_ms(fl)
        p2_ok, p2_det, _ = sc.p2_check(fl, bl)
        collections = sum(int(e.get("heap_valve_collections") or 0) for e, _ in bl)
        if a_raw:
            ok = len(a_live) < len(a_raw) and t_live <= t_raw and p2_ok
            g3.append(ok)
            if not ok:
                failures.append("G3 %s: aborts %d -> %d, total %d -> %d ms, P2 %s" % (
                    ds, len(a_raw), len(a_live), t_raw, t_live, "ok" if p2_ok else "FAIL (%s)" % p2_det))
        if collections == 0:
            ok = abs(t_live / t_raw - 1) <= 0.10 if t_raw else False
            g4.append(ok)
            if not ok:
                failures.append("G4 %s: no collection, totals %d vs %d ms" % (ds, t_raw, t_live))
        lines.append([ds, len(a_raw), len(a_live), collections, f_raw, t_raw, f_live, t_live,
                      "%.3f" % (t_raw / f_raw if f_raw else float("nan")), "%.3f" % (t_live / f_live if f_live else float("nan")),
                      "ok" if p2_ok else "FAIL"])
    verdicts = []
    if g1_n == 0:
        verdicts.append("G1 not testable: bud3 aborted 0 times")
    else:
        ok = g1_heap == g1_n and g1_garbage >= 0.8 * g1_n
        verdicts.append("G1 %s: %d bud3 aborts, %d caused by the heap guard, %d with the heap below the guard after one collection"
                        % ("ok" if ok else "FAIL", g1_n, g1_heap, g1_garbage))
        if not ok:
            failures.append("G1")
    if g2_q == 0:
        verdicts.append("G2 not testable: no bud3live query")
    else:
        verdicts.append("G2 %s: %d bud3live queries compared with F, %d disagreeing" % ("ok" if g2_bad == 0 else "FAIL", g2_q, g2_bad))
        if g2_bad:
            failures.append("G2")
    verdicts.append("G3 not testable: no dataset where bud3 aborted" if not g3 else
                    "G3 %s: %d of %d datasets where bud3 aborted" % ("ok" if all(g3) else "FAIL", sum(g3), len(g3)))
    verdicts.append("G4 not testable: the live guard collected on every dataset" if not g4 else
                    "G4 %s: %d of %d datasets without a collection within 10%%" % ("ok" if all(g4) else "FAIL", sum(g4), len(g4)))
    return lines, failures, verdicts


def ledger_pairs(out):
    rows = sc.read_tsv(out / "cells.tsv")
    by = {}
    for r in rows:
        if r["experiment"] in ("E2G-raw", "E2G-live") and r["status"] == "OK" and r["run_dir"]:
            by.setdefault(r["dataset"], {})[r["experiment"]] = ROOT / r["run_dir"]
    return [(ds, d["E2G-raw"], d["E2G-live"]) for ds, d in by.items() if len(d) == 2]


def report(pairs):
    lines, failures, verdicts = analyze(pairs)
    head = ["dataset", "aborts_bud3", "aborts_bud3live", "live_guard_collections", "F_ms(raw run)", "bud3_ms",
            "F_ms(live run)", "bud3live_ms", "bud3/F", "bud3live/F", "P2 bud3live"]
    print("\t".join(head))
    for l in lines:
        print("\t".join(str(x) for x in l))
    print("pairs compared: %d" % len(pairs))
    for v in verdicts:
        print(v)
    for f in failures:
        print("  FAILED: " + f)
    return 1 if failures else 0


EX_HEAD = ("dataset,policy,batches,schedule,fault,query,after_batch,n_seqs,oracle_size,returned,missing,extra,utility_mismatch,"
           "period_mismatch,increment_seqs,threshold,candidates,fresh,gap_rejected,tracked_after,action,r,kept_on_abort,budget_ms,"
           "attempt_ms,attempt_load_ms,attempt_preprocess_ms,attempt_enum_ms,attempt_rebuild_ms,abort_phase,heap_guard_mb,"
           "heap_start_mb,heap_abort_mb,heap_live_after_abort_mb,heap_valve_collections,abort_cause").split(",")
TM_HEAD = "dataset,policy,batches,schedule,fault,query,after_batch,ingest_ms,query_ms,discovery_mine_ms,rebuild_ms,seed_ms".split(",")


def write_run(d, bud, queries):
    """queries: [(f_ms, bud_ms, action, cause, after_mb, collections, mismatch)]; seed 100 ms, guard 1000 MB."""
    d.mkdir(parents=True)
    with open(d / "exactness.csv", "w", newline="") as fe, open(d / "timing_feasibility.csv", "w", newline="") as ft:
        we, wt = csv.DictWriter(fe, EX_HEAD), csv.DictWriter(ft, TM_HEAD)
        we.writeheader(); wt.writeheader()
        for pol in ("remine-nogc", bud):
            for q, (f_ms, b_ms, act, cause, after, coll, bad) in enumerate(queries):
                isf = pol == "remine-nogc"
                we.writerow({"dataset": "X", "policy": pol, "query": q, "increment_seqs": 10 * (q + 1), "budget_ms": 0 if isf else 500,
                             "action": "remine" if isf else act, "abort_cause": "" if isf else cause, "heap_guard_mb": 1000,
                             "heap_live_after_abort_mb": after, "heap_valve_collections": 0 if isf else coll,
                             "missing": 0 if isf else bad})
                wt.writerow({"dataset": "X", "policy": pol, "query": q, "ingest_ms": 0, "query_ms": f_ms if isf else b_ms,
                             "seed_ms": (0 if isf else 100) if q == 0 else ""})


def self_test():
    results = []
    tmp = Path(tempfile.mkdtemp(prefix="heap_guard_selftest_"))

    def case(name, raw, live, expect_fail):
        write_run(tmp / name / "raw", "bud3", raw)
        write_run(tmp / name / "live", "bud3live", live)
        _, failures, verdicts = analyze([("X", tmp / name / "raw", tmp / name / "live")])
        got = [f.split()[0] for f in failures]
        ok = sorted(got) == sorted(expect_fail)
        results.append((ok, name, got, verdicts))

    good_raw = [(1000, 1000, "remine", "", -1, 0, 0), (1000, 1600, "aborted", "heap", 200, 0, 0), (1000, 1050, "skipped", "", -1, 0, 0)]
    good_live = [(1000, 1000, "remine", "", -1, 0, 0), (1000, 300, "discover", "", -1, 1, 0), (1000, 300, "discover", "", -1, 0, 0)]
    case("garbage aborts fixed by the live guard", good_raw, good_live, [])
    case("abort with the heap still above the guard",
         [good_raw[0], (1000, 1600, "aborted", "heap", 1200, 0, 0), good_raw[2]], good_live, ["G1"])
    case("abort by the time budget", [good_raw[0], (1000, 1600, "aborted", "deadline", -1, 0, 0), good_raw[2]], good_live, ["G1"])
    case("bud3live disagrees with F", good_raw, [good_live[0], (1000, 300, "discover", "", -1, 1, 1), good_live[2]], ["G2"])
    case("bud3live slower than bud3", good_raw, [good_live[0], (1000, 3000, "discover", "", -1, 1, 0), good_live[2]], ["G3"])
    no_abort = [(1000, 1000, "remine", "", -1, 0, 0), (1000, 300, "discover", "", -1, 0, 0)]
    case("no abort, no collection, same cost", no_abort, no_abort, [])
    case("no abort, no collection, 30% apart", no_abort, [no_abort[0], (1000, 800, "discover", "", -1, 0, 0)], ["G4"])
    _, _, v = analyze([])
    results.append((all("not testable" in x for x in v), "no pairs: every check not testable", [], v))
    for ok, name, got, _ in results:
        print("%-50s %s %s" % (name, "ok" if ok else "FAILED", got))
    bad = [r for r in results if not r[0]]
    print("heap guard summary self-test: %d/%d checks passed" % (len(results) - len(bad), len(results)))
    return 1 if bad else 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="results/9950x")
    ap.add_argument("--pair", nargs=3, action="append", metavar=("DATASET", "RAW_DIR", "LIVE_DIR"))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    pairs = [(ds, Path(r), Path(l)) for ds, r, l in args.pair] if args.pair else ledger_pairs(ROOT / args.out)
    return report(pairs)


if __name__ == "__main__":
    sys.exit(main())
