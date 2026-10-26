#!/usr/bin/env python3
"""Run the measurement campaign described in experiments/campaign_9950x.json, one cell at a time.

A cell is (experiment, dataset, schedule, policies, threads, repetition). Every cell runs in its own JVM
(test.QueryScheduleProbe in measurement mode) and, when that JVM has ended, one row is appended to the
ledger <out>/cells.tsv; the run directory, the ledger and the cell's console log are then committed and
pushed to the campaign branch, and one line goes to the run-status channel. A cell that already has a
row is skipped, whatever its status (OK, FAIL, TIMEOUT, OOM, CRASH), so running the same command again
continues an interrupted campaign; a cell interrupted mid-run has no row and runs again.

    python3 tools/campaign.py plan   [--exp E2]                       # cells and which are pending
    python3 tools/campaign.py lock                                    # record the environment lock
    python3 tools/campaign.py run    --exp E2 [--hours H] [--dry-run] [--no-git] [--redo]
    python3 tools/campaign.py status [--exp E2]                       # done / pending per experiment
    python3 tools/campaign.py --self-test                             # the runner's own checks

Before any cell, `run` refuses when: MEASUREMENT_MACHINE.json is missing or describes another host or Java;
<out>/ENV_LOCK.json is missing or its Java build or vendor differs; the tracked tree has uncommitted
changes; a dataset file does not hash to its manifest; the machine runs on battery. It keeps the machine
awake for the whole campaign. Standard library only; runs on Windows and macOS.
"""
import argparse
import datetime
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "experiments" / "campaign_9950x.json"
LEDGER_COLUMNS = ["key", "experiment", "dataset", "schedule", "policies", "threads", "rep", "status", "exit_code",
                  "run_dir", "commit", "java", "started", "ended", "seconds"]
FAULT = int(os.environ.get("CAMPAIGN_FAULT", "0"))   # deliberate defects, for the self-test only


# ------------------------------------------------------------------ cells

def load_config(path=CONFIG):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def cells(cfg, only=None):
    """Every cell of the selected experiments, in a fixed order."""
    out = []
    for exp, spec in cfg["experiments"].items():
        if only and exp not in only:
            continue
        for ds in spec["datasets"]:
            if ds not in cfg["datasets"]:
                raise SystemExit("experiment %s names dataset %s, which the dataset table lacks" % (exp, ds))
            for sch in spec["schedules"]:
                for th in spec.get("threads", [cfg["threads"]]):
                    for rep in range(spec.get("reps", 1)):
                        pol = ",".join(spec["policies"])
                        key = "|".join([exp, ds, sch, pol, "t%d" % th, "r%d" % rep])
                        out.append({"key": key, "experiment": exp, "dataset": ds, "schedule": sch, "policies": pol,
                                    "threads": th, "rep": rep, "oracle": spec["oracle"], "limit_s": spec["limit_s"]})
    return out


def cell_id(c):
    """Short, file-name-safe id used in the run directory name."""
    pol = "+".join(p.split("-")[0] for p in c["policies"].split(","))
    return "%s-%s-%s-t%d-r%d" % (c["experiment"], c["schedule"], pol, c["threads"], c["rep"])


def command(cfg, c):
    d = cfg["datasets"][c["dataset"]]
    s = cfg["schedules"][c["schedule"]]
    tmpl = cfg.get("command")
    if tmpl:                                                   # the self-test replaces the JVM by a stub
        return [a.format(cell=cell_id(c), out=cfg["out"], dataset=c["dataset"]) for a in tmpl]
    return (["java"] + cfg["jvm"] +
            ["-Dthreads=%d" % c["threads"], "-Ddold=%s" % s["dold"], "-Doracle=%s" % c["oracle"],
             "-Dlimit=%d" % c["limit_s"], "-Dout=%s" % cfg["out"], "-Dcell=%s" % cell_id(c),
             "-Dkind=measurement", "-cp", "out", "test.QueryScheduleProbe",
             "datasets/%s_seq.txt" % c["dataset"], "datasets/%s_eui.txt" % c["dataset"],
             d["delta"], str(d["B"]), str(s["batches"]), s["every"], c["policies"]])


# ------------------------------------------------------------------ ledger

def ledger_path(cfg):
    return ROOT / cfg["out"] / "cells.tsv"


def read_ledger(cfg):
    p = ledger_path(cfg)
    if not p.exists():
        return {}
    rows = {}
    with open(p, encoding="utf-8", newline="") as f:
        lines = f.read().split("\n")
    head = lines[0].split("\t")
    for line in lines[1:]:
        if line.strip():
            r = dict(zip(head, line.split("\t")))
            rows.setdefault(r["key"], []).append(r)
    return rows


def append_ledger(cfg, row):
    p = ledger_path(cfg)
    p.parent.mkdir(parents=True, exist_ok=True)
    new = not p.exists()
    with open(p, "a", encoding="utf-8", newline="") as f:
        if new:
            f.write("\t".join(LEDGER_COLUMNS) + "\n")
        f.write("\t".join(str(row.get(k, "")) for k in LEDGER_COLUMNS) + "\n")


# ------------------------------------------------------------------ environment checks

def git(*a, cwd=None, check=False):
    """Run git in cwd, or in ROOT as it is at call time (the self-test points ROOT at a throw-away repository)."""
    return subprocess.run(["git"] + list(a), cwd=str(cwd or ROOT), capture_output=True, text=True, check=check)


def head_commit():
    r = git("rev-parse", "HEAD")
    return r.stdout.strip() if r.returncode == 0 else "unavailable"


def tree_problems():
    r = git("status", "--porcelain", "--untracked-files=no")
    dirty = [l for l in r.stdout.splitlines() if l.strip()]
    return ["the tracked tree has %d uncommitted change(s), first: %s" % (len(dirty), dirty[0])] if dirty else []


def java_build():
    sys.path.insert(0, str(ROOT / "tools"))
    import machine_info
    errors = []
    env, _ = machine_info.current_env(errors)
    return env, errors


def machine_problems():
    sys.path.insert(0, str(ROOT / "tools"))
    import machine_info
    p = ROOT / "MEASUREMENT_MACHINE.json"
    if not p.exists():
        return ["%s does not exist: run python3 tools/machine_info.py --write on the measurement machine first" % p.as_posix()]
    rec = json.loads(p.read_text(encoding="utf-8"))
    return ["%s: %s" % (p.as_posix(), d) for d in machine_info.differences(rec, machine_info.describe())]


def lock_problems(cfg):
    p = ROOT / cfg["out"] / "ENV_LOCK.json"
    if not p.exists():
        return ["%s does not exist: run python3 tools/campaign.py lock first" % p.as_posix()]
    lock = json.loads(p.read_text(encoding="utf-8"))
    env, errors = java_build()
    out = ["java: " + e for e in errors]
    for k in ("java_runtime_version", "java_vendor"):
        if lock.get(k) != env.get(k):
            out.append("%s: locked %r, here %r (%s)" % (k, lock.get(k), env.get(k), p.as_posix()))
    if lock.get("jvm") != cfg["jvm"]:
        out.append("jvm options: locked %r, config %r" % (lock.get("jvm"), cfg["jvm"]))
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def dataset_problems(names):
    """Each built dataset must hash to its manifest; datasets of the benchmark release to datasets/MANIFEST.sha256."""
    out = []
    release = {}
    mf = ROOT / "datasets" / "MANIFEST.sha256"
    if mf.exists():
        for line in mf.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) == 2:
                release[parts[1].lstrip("*")] = parts[0]
    for n in sorted(names):
        seq = ROOT / "datasets" / ("%s_seq.txt" % n)
        if not seq.exists():
            out.append("%s does not exist" % seq.as_posix())
            continue
        man = ROOT / "tools" / "stream_datasets" / "manifests" / ("%s.manifest.json" % n)
        if man.exists():
            want = json.loads(man.read_text(encoding="utf-8"))["seq_sha256"]
            src = man.as_posix()
        elif seq.name in release:
            want, src = release[seq.name], mf.as_posix()
        else:
            out.append("%s: no manifest and not in %s" % (seq.as_posix(), mf.as_posix()))
            continue
        got = sha256(seq)
        if got != want:
            out.append("%s: sha256 %s, but %s says %s" % (seq.as_posix(), got, src, want))
    return out


def on_battery():
    system = platform.system()
    try:
        if system == "Windows":
            import ctypes

            class SPS(ctypes.Structure):
                _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte),
                            ("BatteryLifePercent", ctypes.c_byte), ("SystemStatusFlag", ctypes.c_byte),
                            ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]
            s = SPS()
            if ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)):
                return s.ACLineStatus == 0
        elif system == "Darwin":
            return "Battery Power" in subprocess.run(["pmset", "-g", "batt"], capture_output=True, text=True).stdout
    except Exception:
        pass
    return False


class KeepAwake:
    """Hold the machine awake while the campaign runs (Windows: SetThreadExecutionState; macOS: caffeinate)."""
    def __enter__(self):
        self.proc = None
        system = platform.system()
        if system == "Windows":
            import ctypes
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001 | 0x00000040)
        elif system == "Darwin":
            self.proc = subprocess.Popen(["caffeinate", "-dims", "-w", str(os.getpid())])
        return self

    def __exit__(self, *exc):
        if platform.system() == "Windows":
            import ctypes
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)
        if self.proc:
            self.proc.terminate()


def slept_between(t0, t1):
    """Sleep or dark-wake events between two local times, from the power log (macOS only; None elsewhere)."""
    if platform.system() != "Darwin":
        return None
    a, b = t0.strftime("%Y-%m-%d %H:%M:%S"), t1.strftime("%Y-%m-%d %H:%M:%S")
    out = subprocess.run(["pmset", "-g", "log"], capture_output=True, text=True).stdout
    n = 0
    for line in out.splitlines():
        f = line.split()
        if len(f) > 3 and a <= f[0] + " " + f[1] <= b and f[3] in ("Sleep", "DarkWake"):
            n += 1
    return n


# ------------------------------------------------------------------ status channel and git

def report(cfg, message):
    """Append one line to <run-status>/<project>/<machine>.log, commit and push; never raises."""
    repo = Path(os.environ.get("RUN_STATUS_REPO", str(Path.home() / "work" / "run-status")))
    line = "%s %s" % (datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S"), message)
    print(line, flush=True)
    if not (repo / ".git").exists():
        print("WARNING report: %s is not a git checkout; line not recorded" % repo.as_posix(), flush=True)
        return
    machine = os.environ.get("COMPUTERNAME") or platform.node().split(".")[0]
    d = repo / cfg["status_project"]
    d.mkdir(parents=True, exist_ok=True)
    f = d / (machine + ".log")
    with open(f, "a", encoding="utf-8", newline="") as h:
        h.write(line + "\n")
    for args in (["add", "--", str(f)], ["commit", "-q", "-m", "%s %s: %s" % (cfg["status_project"], machine, message)],
                 ["pull", "-q", "--rebase", "--autostash"], ["push", "-q"]):
        r = git(*args, cwd=repo)
        if r.returncode != 0:
            print("WARNING report: git %s failed: %s" % (args[0], (r.stderr or r.stdout).strip()), flush=True)
            if args[0] in ("add", "commit"):
                return


def checkpoint(cfg, paths, message, use_git):
    """Commit the given paths and push HEAD to the campaign branch; a failed push only warns."""
    if not use_git:
        return
    r = git("add", "-f", "--", *[str(p) for p in paths])
    if r.returncode != 0:
        print("WARNING checkpoint: git add failed: %s" % r.stderr.strip(), flush=True)
        return
    r = git("commit", "-q", "-m", message)
    if r.returncode != 0:
        print("WARNING checkpoint: git commit failed: %s" % (r.stderr or r.stdout).strip(), flush=True)
        return
    branch = "main" if FAULT == 3 else cfg["branch"]            # FAULT 3: pushes to the wrong branch
    r = git("push", "-q", "origin", "HEAD:refs/heads/%s" % branch)
    if r.returncode != 0:
        print("WARNING checkpoint: push failed, the commit is local and leaves with the next push: %s"
              % r.stderr.strip(), flush=True)


# ------------------------------------------------------------------ running

def classify(exit_code, output, killed):
    if killed:
        return "TIMEOUT"
    if "OutOfMemoryError" in output:
        return "OOM"
    if exit_code == 0:
        return "OK"
    if exit_code == 4 or re.search(r"\bTIMEOUT after\b", output):
        return "TIMEOUT"
    if exit_code == 1 and "compared queries=" in output:
        return "FAIL"
    return "CRASH"


def run_cell(cfg, c, use_git, dry_run=False):
    cmd = command(cfg, c)
    if dry_run:
        print("DRY " + " ".join(cmd))
        return None
    if FAULT == 2:                                              # FAULT 2: the row is written before the run
        append_ledger(cfg, dict(c, status="OK", exit_code=0))
    logdir = ROOT / cfg["out"] / "logs"
    logdir.mkdir(parents=True, exist_ok=True)
    started = datetime.datetime.now()
    log = logdir / ("%s_%s.log" % (started.strftime("%Y%m%d_%H%M%S"), cell_id(c) + "_" + c["dataset"]))
    outer = c["limit_s"] * len(c["policies"].split(",")) + 900
    killed = False
    with open(log, "w", encoding="utf-8", newline="\n") as lf:
        lf.write("# " + " ".join(cmd) + "\n# commit %s\n" % head_commit())
        lf.flush()
        p = subprocess.Popen(cmd, cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                             encoding="utf-8", errors="replace")
        chunks = []
        t0 = time.monotonic()
        while True:
            line = p.stdout.readline()
            if line:
                chunks.append(line)
                lf.write(line.replace(str(Path.home()), "<home>"))
                lf.flush()
            elif p.poll() is not None:
                break
            if time.monotonic() - t0 > outer and p.poll() is None:
                p.kill()
                killed = True
        p.wait()
    ended = datetime.datetime.now()
    output = "".join(chunks)
    status = classify(p.returncode, output, killed)
    m = re.search(r"-> (\S.*)$", output.splitlines()[0]) if output else None
    run_dir = m.group(1).strip() if m else ""
    slept = slept_between(started, ended)
    if slept:
        status += "_SLEPT"
    env, _ = java_build()
    row = dict(c, status=status, exit_code=p.returncode, run_dir=Path(run_dir).as_posix() if run_dir else "",
               commit=head_commit(), java="%s %s" % (env.get("java_runtime_version"), env.get("java_vendor")),
               started=started.strftime("%Y-%m-%dT%H:%M:%S"), ended=ended.strftime("%Y-%m-%dT%H:%M:%S"),
               seconds=int((ended - started).total_seconds()))
    if FAULT != 2:
        append_ledger(cfg, row)
    paths = [ledger_path(cfg), log] + ([ROOT / run_dir] if run_dir and (ROOT / run_dir).exists() else [])
    checkpoint(cfg, paths, "Campaign %s: %s %s" % (cfg["branch"], c["key"], status), use_git)
    report(cfg, "%s %s (%d s)" % (cell_id(c) + " " + c["dataset"], status, row["seconds"]))
    return row


def preflight(cfg, todo, require_machine=True):
    problems = []
    if require_machine:
        problems += machine_problems()
        problems += lock_problems(cfg)
        if on_battery():
            problems.append("running on battery: plug in the charger")
    problems += tree_problems()
    problems += dataset_problems({c["dataset"] for c in todo})
    return problems


def cmd_run(cfg, args):
    todo = [c for c in cells(cfg, args.exp) if args.redo or FAULT == 1 or c["key"] not in read_ledger(cfg)]
    print("%d cell(s) pending" % len(todo), flush=True)
    if not todo:
        return 0
    if args.dry_run:
        for c in todo:
            run_cell(cfg, c, False, dry_run=True)
        return 0
    problems = preflight(cfg, todo, require_machine=not args.self_test_mode)
    if problems:
        for p in problems:
            print("REFUSED: " + p, file=sys.stderr)
        return 2
    deadline = time.monotonic() + args.hours * 3600 if args.hours else None
    with KeepAwake():
        report(cfg, "campaign %s start: %d cell(s) pending (%s)" % (cfg["branch"], len(todo), ",".join(args.exp or ["all"])))
        for c in todo:
            if deadline and time.monotonic() > deadline:
                report(cfg, "campaign %s paused after the time budget; run the same command to continue" % cfg["branch"])
                break
            run_cell(cfg, c, not args.no_git)
        else:
            report(cfg, "campaign %s: selected cells done" % cfg["branch"])
    return 0


def cmd_lock(cfg):
    p = ROOT / cfg["out"] / "ENV_LOCK.json"
    env, errors = java_build()
    if errors:
        raise SystemExit("cannot read the Java runtime: " + "; ".join(errors))
    lock = {"java_runtime_version": env["java_runtime_version"], "java_vendor": env["java_vendor"], "jvm": cfg["jvm"],
            "locked_at": datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S"), "host": platform.node()}
    if p.exists():
        old = json.loads(p.read_text(encoding="utf-8"))
        same = all(old.get(k) == lock[k] for k in ("java_runtime_version", "java_vendor", "jvm"))
        print("%s already exists (%s)" % (p.as_posix(), "same environment" if same else "DIFFERENT environment: not replaced"))
        return 0 if same else 2
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(lock, indent=2) + "\n")
    print("-> " + p.as_posix())
    return 0


def cmd_status(cfg, exp):
    led = read_ledger(cfg)
    all_cells = cells(cfg, exp)
    by = {}
    for c in all_cells:
        e = by.setdefault(c["experiment"], {"cells": 0, "pending": 0})
        e["cells"] += 1
        rows = led.get(c["key"])
        if not rows:
            e["pending"] += 1
        else:
            st = rows[-1]["status"]
            e[st] = e.get(st, 0) + 1
    dup = [k for k, v in led.items() if len(v) > 1]
    for e, d in by.items():
        print("%s: %s" % (e, ", ".join("%s=%d" % kv for kv in d.items())))
    if dup:
        print("ledger rows repeated for %d cell(s), first: %s" % (len(dup), dup[0]))
    return 1 if dup else 0


# ------------------------------------------------------------------ self-test

STUB = r'''
import os, sys, time, pathlib
cell, out, ds = sys.argv[1], sys.argv[2], sys.argv[3]
d = pathlib.Path(out) / ("stub_%s_%s_%d" % (ds, cell, time.time_ns()))
d.mkdir(parents=True)
(d / "run_meta.json").write_text("{}\n")
print("%s stub -> %s" % (ds, d.as_posix()), flush=True)
time.sleep(float(os.environ.get("STUB_SLEEP", "0")))
code = int(os.environ.get("STUB_EXIT", "0"))
print("compared queries=1 failing=%d" % (1 if code == 1 else 0))
sys.exit(code)
'''


def self_test():
    """Exercise the runner on a throw-away repository with a stub instead of the JVM."""
    results = []

    def check(ok, msg):
        results.append((bool(ok), msg))

    def scenario(fault):
        tmp = Path(tempfile.mkdtemp(prefix="campaign_selftest_"))
        repo, remote, status = tmp / "repo", tmp / "remote.git", tmp / "status"
        subprocess.run(["git", "init", "-q", "--bare", str(remote)], check=True)
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        for r in (repo,):
            git("config", "user.email", "test@example.invalid", cwd=r)
            git("config", "user.name", "test", cwd=r)
        (repo / "tools").mkdir()
        (repo / "experiments").mkdir()
        (repo / "datasets").mkdir()
        (repo / "stub.py").write_text(STUB)
        for ds in ("A", "B"):
            (repo / "datasets" / ("%s_seq.txt" % ds)).write_text(ds + "\n")
        (repo / "datasets" / "MANIFEST.sha256").write_text(
            "".join("%s  %s_seq.txt\n" % (sha256(repo / "datasets" / ("%s_seq.txt" % ds)), ds) for ds in ("A", "B")))
        cfg = {"out": "results/9950x", "branch": "campaign-test", "status_project": "selftest", "jvm": ["-Xmx1g"],
               "threads": 2, "command": [sys.executable, "stub.py", "{cell}", "{out}", "{dataset}"],
               "datasets": {"A": {"delta": "0.1", "B": 1, "role": "x"}, "B": {"delta": "0.1", "B": 1, "role": "x"}},
               "schedules": {"S1": {"dold": "0.5", "batches": 3, "every": "every"}},
               "experiments": {"E": {"datasets": ["A", "B"], "schedules": ["S1"], "policies": ["p"], "oracle": "first",
                                     "reps": 1, "limit_s": 60}}}
        (repo / "experiments" / "campaign_9950x.json").write_text(json.dumps(cfg))
        (repo / ".gitignore").write_text("results/\n")
        git("add", "-A", cwd=repo)
        git("commit", "-q", "-m", "init", cwd=repo)
        git("remote", "add", "origin", str(remote), cwd=repo)
        git("push", "-q", "origin", "HEAD:refs/heads/main", cwd=repo)
        git("init", "-q", str(status))
        git("config", "user.email", "test@example.invalid", cwd=status)
        git("config", "user.name", "test", cwd=status)
        env = dict(os.environ, RUN_STATUS_REPO=str(status), CAMPAIGN_FAULT=str(fault))
        return tmp, repo, remote, env

    def run(repo, env, *extra, wait=True):
        cmd = [sys.executable, str(Path(__file__).resolve()), "--root", str(repo), "run", "--exp", "E", "--self-test-mode"] + list(extra)
        if wait:
            return subprocess.run(cmd, capture_output=True, text=True, env=env, timeout=120)
        return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env)

    def ledger_rows(repo):
        p = repo / "results" / "9950x" / "cells.tsv"
        return [l for l in p.read_text().splitlines()[1:] if l.strip()] if p.exists() else []

    # 1. two runs: each cell recorded once, the second run skips everything; the push lands on the campaign branch
    tmp, repo, remote, env = scenario(0)
    r1 = run(repo, env)
    r2 = run(repo, env)
    rows = ledger_rows(repo)
    check(r1.returncode == 0 and len(rows) == 2, "first run records both cells (rows=%d)" % len(rows))
    check("0 cell(s) pending" in r2.stdout and len(ledger_rows(repo)) == 2, "second run skips recorded cells")
    br = git("ls-remote", str(remote), cwd=repo).stdout
    check("refs/heads/campaign-test" in br, "checkpoint pushed to the campaign branch")
    check(len(git("log", "--oneline", cwd=repo).stdout.splitlines()) == 3, "one commit per cell")

    # 2. interruption: a cell killed mid-run leaves no row and runs again
    tmp, repo, remote, env = scenario(0)
    env_slow = dict(env, STUB_SLEEP="5")
    p = run(repo, env_slow, wait=False)
    time.sleep(2.5)
    p.kill()
    p.wait()
    killed_rows = len(ledger_rows(repo))
    run(repo, env)
    check(killed_rows == 0 and len(ledger_rows(repo)) == 2, "interrupted cell has no row and runs on restart (rows after kill=%d)" % killed_rows)

    # 3. a push that fails does not stop the campaign
    tmp, repo, remote, env = scenario(0)
    git("remote", "set-url", "origin", str(tmp / "missing.git"), cwd=repo)
    r = run(repo, env)
    check(r.returncode == 0 and len(ledger_rows(repo)) == 2 and "push failed" in r.stdout, "failed push only warns")

    # 4. statuses: exit 1 with a comparison is FAIL, exit 4 is TIMEOUT
    check(classify(1, "compared queries=3 failing=1", False) == "FAIL", "exit 1 after a comparison is FAIL")
    check(classify(4, "x TIMEOUT after 60 s", False) == "TIMEOUT", "watchdog halt is TIMEOUT")
    check(classify(0, "java.lang.OutOfMemoryError", False) == "OOM", "out of memory is OOM")
    check(classify(137, "", True) == "TIMEOUT", "outer kill is TIMEOUT")

    # 5. a dataset that does not hash to its manifest refuses the run
    tmp, repo, remote, env = scenario(0)
    (repo / "datasets" / "A_seq.txt").write_text("changed\n")
    git("commit", "-qam", "change data", cwd=repo)
    r = run(repo, env)
    check(r.returncode == 2 and "REFUSED" in r.stderr and not ledger_rows(repo), "changed dataset refuses the run")

    # 6. uncommitted tracked change refuses the run
    tmp, repo, remote, env = scenario(0)
    (repo / "stub.py").write_text(STUB + "\n# edited\n")
    r = run(repo, env)
    check(r.returncode == 2 and "uncommitted" in r.stderr, "dirty tree refuses the run")

    # 7. injected faults must be caught by the checks above
    caught = {}
    tmp, repo, remote, env = scenario(1)                         # ignores the ledger
    run(repo, env)
    run(repo, env)
    caught[1] = len(ledger_rows(repo)) != 2
    tmp, repo, remote, env = scenario(2)                         # writes the row before running
    p = run(repo, dict(env, STUB_SLEEP="5"), wait=False)
    time.sleep(2.5)
    p.kill()
    p.wait()
    caught[2] = len(ledger_rows(repo)) != 0
    tmp, repo, remote, env = scenario(3)                         # pushes to the wrong branch
    run(repo, env)
    caught[3] = "refs/heads/campaign-test" not in git("ls-remote", str(remote), cwd=repo).stdout
    for f, ok in caught.items():
        check(ok, "injected fault %d is caught" % f)

    bad = [m for ok, m in results if not ok]
    print("campaign self-test: %d/%d checks passed%s" % (len(results) - len(bad), len(results),
                                                          "" if not bad else "; FAILED: " + " | ".join(bad)))
    return 1 if bad else 0


def main(argv=None):
    global ROOT, CONFIG
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", help=argparse.SUPPRESS)
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    for name in ("plan", "status"):
        s = sub.add_parser(name)
        s.add_argument("--exp", action="append")
    sub.add_parser("lock")
    s = sub.add_parser("run")
    s.add_argument("--exp", action="append")
    s.add_argument("--hours", type=float, default=0)
    s.add_argument("--dry-run", action="store_true")
    s.add_argument("--no-git", action="store_true")
    s.add_argument("--redo", action="store_true")
    s.add_argument("--self-test-mode", action="store_true", help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.root:
        ROOT = Path(args.root).resolve()
        CONFIG = ROOT / "experiments" / "campaign_9950x.json"
    cfg = load_config(CONFIG)
    if args.cmd == "plan":
        led = read_ledger(cfg)
        cs = cells(cfg, args.exp)
        for c in cs:
            print("%-8s %s" % ("done" if c["key"] in led else "pending", c["key"]))
        print("%d cell(s), %d pending" % (len(cs), sum(1 for c in cs if c["key"] not in led)))
        return 0
    if args.cmd == "lock":
        return cmd_lock(cfg)
    if args.cmd == "status":
        return cmd_status(cfg, args.exp)
    if args.cmd == "run":
        return cmd_run(cfg, args)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
