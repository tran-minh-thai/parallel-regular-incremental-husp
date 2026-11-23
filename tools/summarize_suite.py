#!/usr/bin/env python3
"""Summarize the S1-S11 suite campaign (experiments/campaign_9950x_suite.json) and check its invariants.

    python3 tools/summarize_suite.py [--config experiments/campaign_9950x_suite.json] [--tsv out.tsv]
    python3 tools/summarize_suite.py --self-test

Reads the ledger <out>/cells.tsv, the results.csv of every suite cell's run directory and the console log of every
"java" cell (the one-off recall checks). Prints one line per check and writes one TSV row per configuration
(dataset, scenario, distribution, algorithm, threads): measured runs, median runtime, pattern count, statuses.

    U0  every cell of the table has a ledger row; how many are OK
    U1  the pattern count of S1 is identical at every thread count (parallel invariance), per dataset
    U2  every exact method returns exactly the oracle set where an oracle exists (hits = oracle size = pattern count)
    U3  configurations with a TIMEOUT or ERROR row (reported, not a failure: the suite records them as results)
    U4  each recall check: the proposal returns as many patterns as the oracle, all of them in the oracle, at every T
    U5  the control cells (in-miner memory reset on) return the same pattern counts as the same configurations without it

A check over zero comparable cases prints "unverified" and fails. Exit status 1 when any check fails.
Standard library only; runs on Windows and macOS.
"""
import argparse
import json
import re
import shutil
import statistics
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXACT = {"P-RIncHUSP", "P-RIncHUSP-seq", "P-RIncHUSP-invidx", "P-RIncHUSP[reg,disc]", "P-RIncHUSP[-,disc]",
         "ParRemine", "Remine-static"}


def read_ledger(root, cfg):
    p = root / cfg["out"] / "cells.tsv"
    rows = {}
    if p.exists():
        lines = p.read_text(encoding="utf-8").split("\n")
        head = lines[0].split("\t")
        for line in lines[1:]:
            if line.strip():
                r = dict(zip(head, line.split("\t")))
                rows[r["key"]] = r                                     # the last row of a key wins
    return rows


def cells(cfg):
    out = []
    for exp, spec in cfg["experiments"].items():
        for ds, sch in spec["pairs"]:
            for rep in range(spec.get("first_rep", 0), spec.get("first_rep", 0) + spec.get("reps", 1)):
                pol = ",".join(spec["policies"])
                th = spec.get("threads", [cfg["threads"]])[0]
                out.append({"key": "|".join([exp, ds, sch, pol, "t%d" % th, "r%d" % rep]), "experiment": exp,
                            "dataset": ds, "schedule": sch, "kind": spec.get("kind", "probe"), "rep": rep, "threads": th,
                            "policies": pol, "control": "-Dsuite.minerGc=true" in spec.get("props", [])})
    return out


def read_rows(run_dir):
    p = run_dir / "results.csv"
    if not p.exists():
        return None
    # The runner writes the algorithm name unquoted, and names such as "P-RIncHUSP[-,disc]" contain a comma, so
    # those rows carry more fields than the header and csv.DictReader shifts every later column by one. Column
    # names carry no commas: fields before the algorithm are read from the start of the row, the rest from the end.
    lines = p.read_text(encoding="utf-8").splitlines()
    head = lines[0].split(",")
    ai = head.index("algorithm")
    after = len(head) - ai - 1
    out = []
    for n, line in enumerate(lines[1:], 2):
        if not line.strip():
            continue
        f = line.split(",")
        if len(f) < len(head):
            raise ValueError("%s line %d: %d fields, header has %d" % (p, n, len(f), len(head)))
        f = f[:ai] + [",".join(f[ai:len(f) - after])] + f[len(f) - after:]
        out.append(dict(zip(head, f)))
    return out


def cell_id(c):
    pol = "+".join(p.split("-")[0] for p in c["policies"].split(","))
    return "%s-%s-%s-t%d-r%d" % (c["experiment"], c["schedule"], pol, c["threads"], c["rep"])


def recall_log(root, cfg, c):
    logs = sorted((root / cfg["out"] / "logs").glob("*_%s_%s.log" % (cell_id(c), c["dataset"])))
    return logs[-1].read_text(encoding="utf-8", errors="replace") if logs else None


def summarize(root, cfg, tsv=None):
    led = read_ledger(root, cfg)
    cs = cells(cfg)
    lines, fails = [], []

    def say(tag, ok, text):
        lines.append("%s %s: %s" % (tag, "ok" if ok else "FAILED", text))
        if not ok:
            fails.append(tag)

    have = [c for c in cs if c["key"] in led]
    okc = [c for c in have if led[c["key"]]["status"] == "OK"]
    say("U0", len(have) == len(cs) and len(okc) == len(cs) and cs,
        "%d of %d cells recorded, %d OK%s" % (len(have), len(cs), len(okc),
                                              "" if len(okc) == len(cs) else "; not OK: " + ", ".join(
                                                  "%s=%s" % (c["key"], led[c["key"]]["status"] if c["key"] in led else "missing")
                                                  for c in cs if c not in okc)[:400]))

    rows = []                                                          # (cell, row) for every suite row
    for c in have:
        if c["kind"] != "suite" or not led[c["key"]].get("run_dir"):
            continue
        rr = read_rows(root / led[c["key"]]["run_dir"])
        for r in rr or []:
            rows.append((c, r))

    def ok_rows(pred):
        return [(c, r) for c, r in rows if r["status"] == "OK" and pred(c, r)]

    # U1 parallel invariance
    by_ds = {}
    for c, r in ok_rows(lambda c, r: r["scenario"] == "S1-scalability" and not c["control"]):
        by_ds.setdefault(r["dataset"], {}).setdefault(r["threads"], set()).add(r["hs_count"])
    bad = {ds: v for ds, v in by_ds.items() if len(set().union(*v.values())) != 1}
    n_t = sum(len(v) for v in by_ds.values())
    say("U1", by_ds and not bad, ("unverified: no S1 row" if not by_ds else
        "%d datasets, %d (dataset, T) groups; differing: %s" % (len(by_ds), n_t, bad or "none")))

    # U2 exactness against the oracle
    cmp_ = ok_rows(lambda c, r: r["algorithm"] in EXACT and int(r["oracle_size"]) >= 0)
    wrong = [(r["dataset"], r["scenario"], r["distribution"], r["algorithm"], r["hs_count"], r["oracle_hits"], r["oracle_size"])
             for c, r in cmp_ if not (r["hs_count"] == r["oracle_hits"] == r["oracle_size"])]
    say("U2", cmp_ and not wrong, ("unverified: no exact row with an oracle" if not cmp_ else
        "%d measured runs of exact methods compared with the oracle, %d differ%s" % (
            len(cmp_), len(wrong), "" if not wrong else ": " + str(wrong[:5]))))

    # U3 configurations that ran out of time or memory
    failed = sorted({(r["dataset"], r["scenario"], r["distribution"], r["algorithm"], r["threads"], r["status"])
                     for c, r in rows if r["status"] != "OK"})
    lines.append("U3 info: %d configuration(s) with a TIMEOUT or ERROR row%s" % (
        len(failed), "" if not failed else ": " + "; ".join("|".join(x) for x in failed)))

    # U4 recall checks
    jc = [c for c in have if c["kind"] == "java"]
    n4, bad4 = 0, []
    for c in jc:
        text = recall_log(root, cfg, c) or ""
        m = re.search(r"oracle HS=(\d+)", text)
        ts = re.findall(r"T=(\d+)\s*\| HS=(\d+) correct=(\d+)", text)
        if not m or not ts:
            bad4.append("%s: no oracle or thread line in its log" % c["dataset"])
            continue
        for t, hs, corr in ts:
            n4 += 1
            if not (hs == corr == m.group(1)):
                bad4.append("%s T=%s: HS=%s correct=%s oracle=%s" % (c["dataset"], t, hs, corr, m.group(1)))
    if jc:
        say("U4", n4 > 0 and not bad4, "%d (dataset, T) recall checks over %d cell(s); %s" % (
            n4, len(jc), "; ".join(bad4) if bad4 else "all exact"))

    # U5 control against the main cells
    ctl = {}
    for c, r in ok_rows(lambda c, r: c["control"]):
        ctl[(r["dataset"], r["scenario"], r["distribution"], r["algorithm"], r["threads"])] = r["hs_count"]
    main = {}
    for c, r in ok_rows(lambda c, r: not c["control"]):
        main.setdefault((r["dataset"], r["scenario"], r["distribution"], r["algorithm"], r["threads"]), set()).add(r["hs_count"])
    common = [k for k in ctl if k in main]
    diff = [k for k in common if main[k] != {ctl[k]}]
    if ctl:
        say("U5", common and not diff, ("unverified: no configuration in both" if not common else
            "%d configurations in both, %d with a different pattern count%s" % (len(common), len(diff), "" if not diff else ": " + str(diff[:5]))))

    # per-configuration table
    agg = {}
    for c, r in rows:
        k = (r["dataset"], r["scenario"], r["distribution"], r["algorithm"], r["threads"], "control" if c["control"] else "main")
        a = agg.setdefault(k, {"ms": [], "hs": set(), "status": set()})
        a["status"].add(r["status"])
        if r["status"] == "OK" and int(r["runtime_ms"]) >= 0:
            a["ms"].append(int(r["runtime_ms"]))
            a["hs"].add(r["hs_count"])
    if tsv:
        with open(tsv, "w", encoding="utf-8", newline="\n") as f:
            f.write("dataset\tscenario\tdistribution\talgorithm\tthreads\tarm\tn_ok\tmedian_ms\ths_count\tstatuses\n")
            for k in sorted(agg):
                a = agg[k]
                f.write("\t".join(list(k) + [str(len(a["ms"])), str(int(statistics.median(a["ms"]))) if a["ms"] else "",
                                             ",".join(sorted(a["hs"])), ",".join(sorted(a["status"]))]) + "\n")
    lines.append("configurations: %d (from %d measured rows)" % (len(agg), len(rows)))
    return lines, fails


# ------------------------------------------------------------------ self-test

HEADER = ("dataset,scenario,distribution,algorithm,mu,minUtilRatio,maxRegRatio,threads,n_batches,iteration,runtime_ms,"
          "build_ms,incr_ms,disc_ms,peak_mb,hs_count,shs_count,recall,status,seed_mb,incr_mb,disc_mb,tracked_seed,tracked,"
          "oracle_size,oracle_hits,abs_b")


def self_test():
    results = []

    def check(ok, msg):
        results.append((bool(ok), msg))

    def build(fault=0):
        tmp = Path(tempfile.mkdtemp(prefix="summarize_suite_"))
        cfg = {"out": "results/9950x", "threads": 16, "experiments": {
            "SUITE": {"kind": "suite", "pairs": [["A", "S1"], ["A", "S8"]], "policies": ["suite"], "limit_s": 1},
            "SUITE-GC": {"kind": "suite", "pairs": [["A", "S8"]], "policies": ["suite"], "limit_s": 1,
                         "props": ["-Dsuite.minerGc=true"]},
            "RECALL": {"kind": "java", "pairs": [["B", "once"]], "policies": ["recall"], "limit_s": 1}}}
        out = tmp / "results/9950x"
        (out / "logs").mkdir(parents=True)
        led = ["key\tstatus\trun_dir"]

        def row(ds, sc, dist, alg, t, hs, hits, osz, status="OK"):
            return "%s,%s,%s,%s,x,0.1,0.1,%d,4,1,10,0,0,0,1.0,%d,0,1.0,%s,0,0,0,0,0,%d,%d,0" % (ds, sc, dist, alg, t, hs, status, osz, hits)

        cellrows = {
            "SUITE|A|S1|suite|t16|r0": [row("A", "S1-scalability", "A-Uniform", "P-RIncHUSP", t,
                                            (9 if fault == 1 and t == 16 else 10), -1, -1) for t in (1, 2, 16)],
            "SUITE|A|S8|suite|t16|r0": [row("A", "S8-batchscale", "B=4", "P-RIncHUSP", 16, 10, 10, 10),
                                        row("A", "S8-batchscale", "B=4", "ParRemine", 16, 10, (9 if fault == 2 else 10), 10),
                                        row("A", "S8-batchscale", "B=4", "RIncHusp-Fix0.9", 1, 8, 8, 10),
                                        row("A", "S8-batchscale", "B=4", "P-RIncHUSP[-,disc]", 16, 10, (9 if fault == 6 else 10), 10),
                                        row("A", "S8-batchscale", "B=64", "ParRemine", 16, -1, -1, 10, "TIMEOUT")],
            "SUITE-GC|A|S8|suite|t16|r0": [row("A", "S8-batchscale", "B=4", "ParRemine", 16, (11 if fault == 4 else 10), -1, -1)],
        }
        for i, (k, rs) in enumerate(cellrows.items()):
            if fault == 5 and k.startswith("SUITE-GC"):
                continue
            d = out / "suite" / ("run_%d" % i)
            d.mkdir(parents=True)
            (d / "results.csv").write_text(HEADER + "\n" + "\n".join(rs) + "\n", encoding="utf-8")
            led.append("%s\tOK\t%s" % (k, d.relative_to(tmp).as_posix()))
        led.append("RECALL|B|once|recall|t16|r0\tOK\t")
        (out / "logs" / "20261122_000000_RECALL-once-recall-t16-r0_B.log").write_text(
            "oracle HS=30 (5 ms)\n  T=1   | HS=30 correct=30 recall=100%%\n  T=16  | HS=30 correct=%d recall=x\n"
            % (29 if fault == 3 else 30), encoding="utf-8")
        (out / "cells.tsv").write_text("\n".join(led) + "\n", encoding="utf-8")
        return tmp, cfg

    tmp, cfg = build(0)
    lines, fails = summarize(tmp, cfg, tmp / "s.tsv")
    check(not fails, "clean table passes every check (%s)" % fails)
    check(any(l.startswith("U3 info: 1 ") for l in lines), "a TIMEOUT configuration is listed, not failed")
    t = (tmp / "s.tsv").read_text(encoding="utf-8").splitlines()
    check(len(t) == 1 + 9 and all(len(x.split("\t")) == 10 for x in t), "TSV has one row per configuration and arm (%d)" % (len(t) - 1))
    shutil.rmtree(tmp, ignore_errors=True)
    for f, tag in ((1, "U1"), (2, "U2"), (3, "U4"), (4, "U5"), (5, "U0"), (6, "U2")):
        tmp, cfg = build(f)
        _, fails = summarize(tmp, cfg)
        check(fails == [tag], "fault %d is caught by %s and nothing else (%s)" % (f, tag, fails))
        shutil.rmtree(tmp, ignore_errors=True)
    tmp = Path(tempfile.mkdtemp(prefix="summarize_suite_"))
    _, fails = summarize(tmp, {"out": "results/9950x", "threads": 16, "experiments": {
        "SUITE": {"kind": "suite", "pairs": [["A", "S1"]], "policies": ["suite"], "limit_s": 1}}})
    check("U0" in fails and "U1" in fails and "U2" in fails, "an empty ledger is unverified, not passed (%s)" % fails)
    shutil.rmtree(tmp, ignore_errors=True)
    bad = [m for ok, m in results if not ok]
    print("summarize_suite self-test: %d/%d checks passed%s" % (len(results) - len(bad), len(results),
                                                                "" if not bad else "; FAILED: " + " | ".join(bad)))
    return 1 if bad else 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="experiments/campaign_9950x_suite.json")
    ap.add_argument("--tsv", help="per-configuration table (default: <out>/summary_SUITE.tsv)")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args(argv)
    if a.self_test:
        return self_test()
    p = Path(a.config)
    cfg = json.loads((p if p.is_absolute() else ROOT / p).read_text(encoding="utf-8"))
    tsv = Path(a.tsv) if a.tsv else ROOT / cfg["out"] / "summary_SUITE.tsv"
    lines, fails = summarize(ROOT, cfg, tsv)
    print("\n".join(lines))
    print("-> %s" % tsv.as_posix())
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
