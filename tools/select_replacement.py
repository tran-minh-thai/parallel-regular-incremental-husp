#!/usr/bin/env python3
"""Pick the replacement dataset by the rule declared before the measurement, from the regime cells of a campaign.

    python3 tools/select_replacement.py --config experiments/campaign_9950x_dunnhumby.json [--write]
    python3 tools/select_replacement.py --self-test

The config's "replacement" entry names the regime experiment (one test.RemineTimer cell per candidate), the candidate
groups (one group per source, candidates in their declared order), the threshold in ms and the experiment to generate.
The rule, per group: the first candidate, in the declared order, whose median full re-mine is at least the threshold; a
group with none chooses nothing. Every candidate must have a recorded OK cell whose log carries a "median ms=" line,
otherwise nothing is chosen (exit 2). No group choosing anything: exit 1. With --write, the pairs of every chosen dataset
(one per schedule of the template) are written into the config as the generated experiment, and
<out>/replacement_choice.txt records every median and the decision. Standard library only.
"""
import argparse
import importlib.util
import json
import re
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def campaign_module():
    spec = importlib.util.spec_from_file_location("campaign", ROOT / "tools" / "campaign.py")
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def decide(root, cfg, camp):
    rep = cfg["replacement"]
    led = {}
    p = root / cfg["out"] / "cells.tsv"
    if p.exists():
        lines = p.read_text(encoding="utf-8").splitlines()
        head = lines[0].split("\t")
        for line in lines[1:]:
            if line.strip():
                r = dict(zip(head, line.split("\t")))
                led[r["key"]] = r
    logs = root / cfg["out"] / "logs"
    medians, problems = {}, []
    for c in camp.cells(cfg, only={rep["experiment"]}):
        r = led.get(c["key"])
        if not r or r.get("status") != "OK":
            problems.append("%s: %s" % (c["key"], "no ledger row" if not r else r.get("status")))
            continue
        pat = re.compile(r"^\d{8}_\d{6}_" + re.escape(camp.cell_id(c) + "_" + c["dataset"]) + r"\.log$")
        found = sorted(f for f in (logs.iterdir() if logs.exists() else []) if pat.match(f.name))
        m = re.search(r"^median ms=(\d+) patterns=(\d+)", found[-1].read_text(encoding="utf-8", errors="replace"), re.M) if found else None
        if not m:
            problems.append("%s: no log with a median line" % c["key"])
            continue
        medians[c["dataset"]] = (int(m.group(1)), int(m.group(2)))
    every = [d for g in groups(rep) for d in g["candidates"]]
    missing = [d for d in every if d not in medians]
    if problems or missing:
        return None, medians, problems + ["no regime measurement for %s" % d for d in missing]
    chosen = []
    for g in groups(rep):
        for d in g["candidates"]:
            if medians[d][0] >= rep["threshold_ms"]:
                chosen.append(d)
                break
    return chosen, medians, []


def groups(rep):
    return rep.get("groups") or [{"name": "candidates", "candidates": rep["candidates"]}]


def write(root, cfg_path, cfg, chosen, medians):
    rep = cfg["replacement"]
    spec = dict(rep["template"])
    spec["pairs"] = [[d, s] for d in chosen for s in rep["schedules"]]
    cfg["experiments"][rep["target"]] = spec
    cfg_path.write_text(json.dumps(cfg, indent=2) + "\n", encoding="utf-8")
    lines = ["group\tcandidate\tmedian_ms\tpatterns\tdecision"]
    for g in groups(rep):
        for d in g["candidates"]:
            ms, pc = medians[d]
            lines.append("%s\t%s\t%d\t%d\t%s" % (g["name"], d, ms, pc, "CHOSEN" if d in chosen else
                                                 ("below threshold" if ms < rep["threshold_ms"] else
                                                  "not reached (an earlier candidate of the group passed)")))
    lines.append("# rule, per group: first candidate in the declared order whose median full re-mine is >= %d ms" % rep["threshold_ms"])
    out = root / cfg["out"]
    out.mkdir(parents=True, exist_ok=True)
    (out / "replacement_choice.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--config")
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args(argv)
    if a.self_test:
        return self_test()
    cfg_path = ROOT / a.config
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    chosen, medians, problems = decide(ROOT, cfg, campaign_module())
    for g in groups(cfg["replacement"]):
        for d in g["candidates"]:
            if d in medians:
                print("%-22s median %6d ms, %d patterns" % (d, medians[d][0], medians[d][1]))
    if problems:
        print("REFUSED: " + "; ".join(problems))
        return 2
    if not chosen:
        print("no candidate reaches %d ms: nothing chosen" % cfg["replacement"]["threshold_ms"])
        return 1
    print("chosen: %s" % ", ".join(chosen))
    if a.write:
        write(ROOT, cfg_path, cfg, chosen, medians)
        print("wrote experiment %s into %s" % (cfg["replacement"]["target"], a.config))
    return 0


def self_test():
    camp = campaign_module()
    res = []

    def build(meds, drop=None):
        tmp = Path(tempfile.mkdtemp(prefix="select_replacement_"))
        cfg = {"out": "results/x", "threads": 16, "jvm": [],
               "datasets": {"A": {"delta": "0.01", "B": 10}, "B": {"delta": "0.01", "B": 10}, "C": {"delta": "0.01", "B": 10}},
               "experiments": {"REG": {"kind": "java", "main": "test.RemineTimer", "pairs": [["A", "regime"], ["B", "regime"], ["C", "regime"]],
                                       "policies": ["timer"], "limit_s": 60, "args": []}},
               "replacement": {"experiment": "REG", "candidates": ["A", "B", "C"], "threshold_ms": 2000, "target": "E2C-X",
                               "schedules": ["S1", "S2", "S3"], "template": {"policies": ["f"], "reps": 3, "limit_s": 60}}}
        out = tmp / "results/x"
        (out / "logs").mkdir(parents=True)
        led = ["key\tstatus"]
        for c, ms in zip(camp.cells(cfg, only={"REG"}), meds):
            if c["dataset"] == drop:
                continue
            led.append("%s\tOK" % c["key"])
            (out / "logs" / ("20261124_120000_%s_%s.log" % (camp.cell_id(c), c["dataset"]))).write_text(
                "run 1 ms=%d patterns=5\nmedian ms=%d patterns=5\n" % (ms, ms), encoding="utf-8")
        (out / "cells.tsv").write_text("\n".join(led) + "\n", encoding="utf-8")
        cp = tmp / "cfg.json"
        cp.write_text(json.dumps(cfg), encoding="utf-8")
        return tmp, cfg, cp

    for meds, want, msg in (([1200, 4000, 11000], ["B"], "the first candidate at or above the threshold is chosen, not the slowest"),
                            ([2000, 4000, 11000], ["A"], "a median equal to the threshold passes"),
                            ([1999, 1500, 1200], [], "no candidate above the threshold chooses nothing")):
        tmp, cfg, cp = build(meds)
        got, _, prob = decide(tmp, cfg, camp)
        res.append((got == want and not prob, "%s (%r)" % (msg, got)))
        shutil.rmtree(tmp, ignore_errors=True)
    tmp, cfg, cp = build([1200, 4000, 11000], drop="A")
    got, _, prob = decide(tmp, cfg, camp)
    res.append((got is None and prob, "a candidate without a measurement refuses the choice (%s)" % prob))
    shutil.rmtree(tmp, ignore_errors=True)
    tmp, cfg, cp = build([1200, 4000, 11000])
    got, meds, _ = decide(tmp, cfg, camp)
    write(tmp, cp, cfg, got, meds)
    w = json.loads(cp.read_text(encoding="utf-8"))
    res.append((w["experiments"]["E2C-X"]["pairs"] == [["B", "S1"], ["B", "S2"], ["B", "S3"]]
                and (tmp / "results/x/replacement_choice.txt").read_text().count("CHOSEN") == 1,
                "--write adds the generated experiment for the chosen dataset and records the decision"))
    shutil.rmtree(tmp, ignore_errors=True)
    for meds, want, msg in (([1200, 4000, 3000], ["B", "C"], "each group chooses its own first candidate at or above the threshold"),
                            ([1200, 1500, 3000], ["C"], "a group with no candidate above the threshold chooses nothing, the other still does")):
        tmp, cfg, cp = build(meds)
        cfg["replacement"]["groups"] = [{"name": "g1", "candidates": ["A", "B"]}, {"name": "g2", "candidates": ["C"]}]
        got, _, prob = decide(tmp, cfg, camp)
        res.append((got == want and not prob, "%s (%r)" % (msg, got)))
        shutil.rmtree(tmp, ignore_errors=True)
    bad = [m for ok, m in res if not ok]
    print("select_replacement self-test: %d/%d checks passed%s" % (len(res) - len(bad), len(res), "" if not bad else "; FAILED: " + " | ".join(bad)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
