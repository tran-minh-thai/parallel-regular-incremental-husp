#!/usr/bin/env python3
"""Check the reserve of the bud3rs and bud3rb policies against their own log, query by query.

    python3 tools/check_reserve.py RUN_DIR [RUN_DIR ...]
    python3 tools/check_reserve.py --self-test

For every bud3rs policy in RUN_DIR/exactness.csv (budget factor c from "-c<factor>", default 1; premium eps from
"-e<eps>", default 0.10) the reserve is recomputed from the logged F_hat, charges and actions by the rule, in whole
microseconds with the same truncation as the miner:

  the first query reaching the gate (an attempt or a refusal) adds floor(c * F_hat) once;
  an attempt must find reserve >= floor(c * F_hat), a refusal ("gated") must find it below;
  a completed attempt adds F_hat - charge, an aborted one subtracts its charge;
  every query after the first adds floor(eps * F_hat).

bud3rb (budget from the reserve) replaces the second rule: the attempt gets budget min(floor(c * F_hat), reserve) and
runs when that budget is positive and at least min(floor_us, floor(c * F_hat)), floor_us being the logged floor (twice
the fixed cost of the previous attempt). Checked there as well: the logged attempt_budget_us equals that budget, the
deadline (budget_ms) is the same budget, every refusal finds reserve < floor(c * F_hat) (bud3rs would refuse too), and
every run of refusals is no longer than ceil((largest floor + largest overrun so far) / smallest premium of the run).

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
    capped = name.split("-")[0] == "bud3rb"
    problems = []
    run_len, run_floor, run_prem, longest = 0, 0, None, (0, 0)   # current refusal run; longest (length, bound)
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
        given = budget
        if act in ("discover", "aborted", "gated"):
            if not opened:
                reserve += budget
                opened = True
                first_budget = budget
            if capped:
                floor = int(r.get("floor_us") or 0)
                given = min(budget, reserve)
                can = given > 0 and given >= min(floor, budget)
                if act == "gated" and can:
                    problems.append("%s q%s: refused with budget %d >= floor %d" % (name, r["query"], given, min(floor, budget)))
                if act == "gated" and reserve >= budget:
                    problems.append("%s q%s: refused although bud3rs would attempt (reserve %d >= budget %d)" % (name, r["query"], reserve, budget))
                if act != "gated" and not can:
                    problems.append("%s q%s: attempted with budget %d below floor %d" % (name, r["query"], given, min(floor, budget)))
                if act != "gated":
                    if int(r.get("attempt_budget_us") or -1) != given:
                        problems.append("%s q%s: logged attempt budget %s, rule gives %d" % (name, r["query"], r.get("attempt_budget_us"), given))
                    if int(r.get("budget_ms") or -1) != given // 1000:
                        problems.append("%s q%s: deadline %s ms is not the attempt budget %d us" % (name, r["query"], r.get("budget_ms"), given))
            else:
                if act == "gated" and reserve >= budget:
                    problems.append("%s q%s: refused with reserve %d >= budget %d" % (name, r["query"], reserve, budget))
                if act != "gated" and reserve < budget:
                    problems.append("%s q%s: attempted with reserve %d < budget %d" % (name, r["query"], reserve, budget))
            if (r["gate"] == "1") != (act == "gated"):
                problems.append("%s q%s: gate column %r disagrees with action %r" % (name, r["query"], r["gate"], act))
        if act == "gated":
            run_len += 1
            run_floor = max(run_floor, int(r.get("floor_us") or 0))
            run_prem = int(eps * fhat) if run_prem is None else min(run_prem, int(eps * fhat))
        elif act in ("discover", "aborted"):
            run_len, run_floor, run_prem = 0, 0, None
        if capped and run_len and eps > 0:
            bound_run = -(-(run_floor + max(0, overrun)) // max(1, run_prem)) + (1 if run_floor == 0 else 0)
            if run_len > longest[0]:
                longest = (run_len, bound_run)
            if run_len > bound_run:
                problems.append("%s q%s: %d refusals in a row, bound %d" % (name, r["query"], run_len, bound_run))
        if act == "discover":
            reserve += fhat - charge
            excess += charge - fhat
            overrun = max(overrun, charge - given)
        elif act == "aborted":
            reserve -= charge
            excess += charge
            overrun = max(overrun, charge - given)
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
    if capped:
        summary += "; longest refusal run %d (bound %d)" % longest
    return checked, problems, summary


def read_run(run_dir):
    d = Path(run_dir)
    ex = list(csv.DictReader(open(d / "exactness.csv", encoding="utf-8", newline="")))
    tm = list(csv.DictReader(open(d / "timing_feasibility.csv", encoding="utf-8", newline="")))
    if len(ex) != len(tm):
        raise ValueError("%s: %d exactness rows, %d timing rows" % (d.as_posix(), len(ex), len(tm)))
    by = {}
    for e, t in zip(ex, tm):
        if e["policy"].split("-")[0] in ("bud3rs", "bud3rb"):
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


def row(q, act, fhat, charge, reserve, query_ms, gate=None, budget=None, floor=None):
    r = {"query": str(q), "action": act, "fhat_us": str(fhat), "charge_us": str(charge), "reserve_us": str(reserve),
         "query_ms": str(query_ms), "gate": gate if gate is not None else ("1" if act == "gated" else "0")}
    if budget is not None:                               # bud3rb: the attempt budget, its deadline in ms, and the floor
        r["attempt_budget_us"], r["budget_ms"] = str(budget), str(budget // 1000)
    if floor is not None:
        r["floor_us"] = str(floor)
    return r


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
    # bud3rb, c = 1, eps = 0.1, microseconds. Early abort: q1 advance 1e6, aborts at 4e5 -> 6e5, premium 1e5 -> 7e5;
    # q2 floor 1e5, budget min(1e6, 7e5) = 7e5, completes at 3e5 -> 1.4e6 + 1e5; q3 full budget, completes at 2e5.
    early = [row(0, "seed", 0, 0, 0, 5), row(1, "aborted", 10**6, 4 * 10**5, 7 * 10**5, 500, budget=10**6, floor=0),
             row(2, "discover", 10**6, 3 * 10**5, 15 * 10**5, 300, budget=7 * 10**5, floor=10**5),
             row(3, "discover", 10**6, 2 * 10**5, 24 * 10**5, 200, budget=10**6, floor=10**5)]
    # Refusals: q1 aborts at 1.1e6 (overrun 1e5) -> -1e5 + 1e5 = 0; q2 budget 0: refused -> 1e5; q3 budget 1e5 >= floor
    # 1e5: aborts at 1.2e5 -> -2e4 + 1e5 = 8e4; q4 8e4 < 1e5: refused -> 1.8e5; q5 budget 1.8e5, completes at 1.5e5.
    refuse = [row(0, "seed", 0, 0, 0, 5), row(1, "aborted", 10**6, 11 * 10**5, 0, 1100, budget=10**6, floor=0),
              row(2, "gated", 10**6, 0, 10**5, 900, floor=10**5),
              row(3, "aborted", 10**6, 12 * 10**4, 8 * 10**4, 1000, budget=10**5, floor=10**5),
              row(4, "gated", 10**6, 0, 18 * 10**4, 900, floor=10**5),
              row(5, "discover", 10**6, 15 * 10**4, 113 * 10**4, 150, budget=18 * 10**4, floor=10**5)]

    def case_rb(name, rows, expect_ok):
        n, p, _ = check_policy("bud3rb", rows)
        ok = (not p and n > 0) if expect_ok else bool(p)
        cases.append((name, ok, "%d checked, %d problems" % (n, len(p))))

    case_rb("clean bud3rb: early abort, then a capped attempt", early, True)
    case_rb("clean bud3rb: refusals below the floor", refuse, True)
    bad = [dict(r) for r in refuse]
    bad[3] = row(3, "aborted", 10**6, 12 * 10**4, 8 * 10**4, 1000, budget=10**6, floor=10**5)
    case_rb("defect 12: the budget ignores the reserve", bad, False)
    bad = [dict(r) for r in refuse]
    bad[3]["budget_ms"] = "1000"                          # logged budget capped, deadline full
    case_rb("defect 13: the deadline keeps the full budget", bad, False)
    bad = [dict(r) for r in refuse]
    bad[4] = row(4, "gated", 10**6, 0, 18 * 10**4, 900, floor=5 * 10**4)   # 8e4 >= floor 5e4: should have attempted
    case_rb("defect: bud3rb refuses a budget above the floor", bad, False)
    bad = [dict(r) for r in early]
    bad[2] = row(2, "discover", 10**6, 3 * 10**5, 15 * 10**5, 300, budget=7 * 10**5, floor=8 * 10**5)  # 7e5 < floor 8e5
    case_rb("defect: bud3rb attempts below the floor", bad, False)
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
