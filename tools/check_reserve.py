#!/usr/bin/env python3
"""Check the reserve of the bud3rs policy against its own log, query by query.

    python3 tools/check_reserve.py RUN_DIR [RUN_DIR ...]
    python3 tools/check_reserve.py --self-test

For every bud3rs policy in RUN_DIR/exactness.csv (budget factor c from "-c<factor>", default 1; premium eps from
"-e<eps>", default 0.10) the reserve is recomputed from the logged F_hat, charges and actions by the rule, in whole
microseconds with the same truncation as the miner:

  the first query reaching the gate (an attempt or a refusal) adds floor(c * F_hat) once;
  an attempt must find reserve >= floor(c * F_hat), a refusal ("gated") must find it below;
  a completed attempt adds F_hat - charge, an aborted one subtracts its charge;
  every query after the first adds floor(eps * F_hat).

Checked per query: the logged reserve equals the recomputed one, the gate decision matches the rule, and the
charge does not exceed the query time the probe measured with its own clock. Per run: total cost minus the run's own
re-mine cost (sum of aborted charges plus completed charges minus their F_hat) is at most
eps * sum F_hat + c * F_hat of the first attempt + the largest overrun of a budget (the cost bound of the reserve).
Prints the denominators. Exit status 1 on any mismatch, or when no bud3rs query could be checked.
"""
import csv
import sys
from pathlib import Path


def params(policy):
    c, eps = 1.0, 0.10
    for part in policy.split("-")[1:]:
        if part.startswith("c"):
            c = float(part[1:])
        elif part.startswith("e"):
            eps = float(part[1:])
    return c, eps


def check_policy(name, rows):
    """rows: dicts merged from exactness.csv and timing_feasibility.csv, in query order. Returns (checked, problems, summary)."""
    c, eps = params(name)
    problems = []
    reserve, opened, first_budget = 0, False, None
    overrun = 0
    excess = 0                                  # sum aborted charges + sum (completed charge - F_hat)
    premiums = 0
    counts = {"discover": 0, "aborted": 0, "gated": 0, "skipped": 0, "other": 0}
    checked = 0
    for i, r in enumerate(rows):
        act = r["action"]
        logged = int(r["reserve_us"] or 0)
        if i == 0:
            if act != "seed" or logged != 0:
                problems.append("%s q0: expected a fused seed with reserve 0, got %r reserve %d" % (name, act, logged))
            continue
        fhat = int(r["fhat_us"] or 0)
        charge = int(r["charge_us"] or 0)
        budget = int(c * fhat)
        if act in ("discover", "aborted", "gated"):
            if not opened:
                reserve += budget
                opened = True
                first_budget = budget
            if act == "gated" and reserve >= budget:
                problems.append("%s q%s: refused with reserve %d >= budget %d" % (name, r["query"], reserve, budget))
            if act != "gated" and reserve < budget:
                problems.append("%s q%s: attempted with reserve %d < budget %d" % (name, r["query"], reserve, budget))
            if (r["gate"] == "1") != (act == "gated"):
                problems.append("%s q%s: gate column %r disagrees with action %r" % (name, r["query"], r["gate"], act))
        if act == "discover":
            reserve += fhat - charge
            excess += charge - fhat
            overrun = max(overrun, charge - budget)
        elif act == "aborted":
            reserve -= charge
            excess += charge
            overrun = max(overrun, charge - budget)
        if act in counts:
            counts[act] += 1
        else:
            counts["other"] += 1
        p = int(eps * fhat)
        reserve += p
        premiums += p
        if act in ("discover", "aborted") and charge > (int(r["query_ms"]) + 1) * 1000:
            problems.append("%s q%s: charge %d us exceeds the probe's query time %s ms" % (name, r["query"], charge, r["query_ms"]))
        if logged != reserve:
            problems.append("%s q%s: logged reserve %d, recomputed %d" % (name, r["query"], logged, reserve))
            reserve = logged                    # resynchronise so one slip is reported once
        checked += 1
    bound = premiums + (first_budget or 0) + max(0, overrun)
    if opened and excess > bound:
        problems.append("%s: cost over the run's own re-mine cost %d us exceeds the bound %d us" % (name, excess, bound))
    summary = "%s: %d queries checked (%s); excess %d us <= bound %d us (premiums %d, advance %d, overrun %d)" % (
        name, checked, " ".join("%s %d" % kv for kv in counts.items() if kv[1]), excess, bound, premiums,
        first_budget or 0, max(0, overrun))
    return checked, problems, summary


def read_run(run_dir):
    d = Path(run_dir)
    ex = list(csv.DictReader(open(d / "exactness.csv", encoding="utf-8", newline="")))
    tm = list(csv.DictReader(open(d / "timing_feasibility.csv", encoding="utf-8", newline="")))
    if len(ex) != len(tm):
        raise ValueError("%s: %d exactness rows, %d timing rows" % (d.as_posix(), len(ex), len(tm)))
    by = {}
    for e, t in zip(ex, tm):
        if e["policy"].split("-")[0] == "bud3rs":
            row = dict(e)
            row["query_ms"] = t["query_ms"]
            by.setdefault(e["policy"], []).append(row)
    return by


def check_runs(dirs):
    total, problems = 0, []
    for d in dirs:
        for name, rows in read_run(d).items():
            n, p, summary = check_policy(name, rows)
            total += n
            problems += p
            print("  %s  %s" % (Path(d).name, summary))
    for p in problems:
        print("PROBLEM " + p)
    print("reserve check: %d queries checked, %d problems" % (total, len(problems)))
    return 1 if problems or total == 0 else 0


def row(q, act, fhat, charge, reserve, query_ms, gate=None):
    return {"query": str(q), "action": act, "fhat_us": str(fhat), "charge_us": str(charge), "reserve_us": str(reserve),
            "query_ms": str(query_ms), "gate": gate if gate is not None else ("1" if act == "gated" else "0")}


def self_test():
    """Hand-computed trajectories (c = 1, eps = 0.1), then each with one injected defect that must be caught."""
    # Aborts: q1 advance 1000, abort 1200 -> -200, premium 100 -> -100; q2 backoff, premium 110 -> 10;
    # q3 refused (10 < 1200), premium 120 -> 130; q4 refused (130 < 1300), premium 130 -> 260.
    aborts = [row(0, "seed", 0, 0, 0, 5), row(1, "aborted", 1000, 1200, -100, 3),
              row(2, "skipped", 1100, 0, 10, 2), row(3, "gated", 1200, 0, 130, 2), row(4, "gated", 1300, 0, 260, 2)]
    # Completions: q1 advance 1000, completes at 300 -> 1700, premium 100 -> 1800; q2 completes at 400 -> 2400 + 100.
    wins = [row(0, "seed", 0, 0, 0, 5), row(1, "discover", 1000, 300, 1800, 1), row(2, "discover", 1000, 400, 2500, 1)]
    cases = []

    def case(name, rows, expect_ok):
        n, p, _ = check_policy("bud3rs-e0.1", rows)
        ok = (not p and n > 0) if expect_ok else bool(p)
        cases.append((name, ok, "%d checked, %d problems" % (n, len(p))))

    case("clean: aborts and refusals", aborts, True)
    case("clean: completions", wins, True)
    bad = [dict(r) for r in aborts]
    for r, v in zip(bad[1:], (1100, 1210, 1330, 1460)):  # the abort never charged: every later reserve 1200 higher
        r["reserve_us"] = str(v)
    case("defect: abort not charged", bad, False)
    bad = [dict(r) for r in aborts]
    bad[3] = row(3, "aborted", 1200, 50, 80, 1)          # attempted although the reserve held 10 < 1200
    case("defect: attempt the reserve cannot pay", bad, False)
    bad = [dict(r) for r in wins]
    bad[2] = row(2, "gated", 1000, 0, 1900, 1)           # refused although the reserve held 1800 >= 1000
    case("defect: refusal the reserve could pay", bad, False)
    bad = [dict(r) for r in wins]
    bad[1] = row(1, "discover", 1000, 3000, -1900, 1)    # charge 3000 us over a 1 ms query
    case("defect: charge above the probe's own clock", bad, False)
    bad = [dict(r) for r in aborts]
    bad[3]["gate"] = "0"
    case("defect: gate column disagrees with the action", bad, False)
    n, p, _ = check_policy("bud3rs", [row(0, "seed", 0, 0, 0, 5)])
    cases.append(("vacuous: nothing after the seed is not a pass", n == 0, "%d checked" % n))
    for name, ok, detail in cases:
        print("%-55s %s  %s" % (name, "ok" if ok else "FAILED", detail))
    bad = [c for c in cases if not c[1]]
    print("self-test: %d/%d cases as expected" % (len(cases) - len(bad), len(cases)))
    return 1 if bad else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(check_runs(sys.argv[1:]))
