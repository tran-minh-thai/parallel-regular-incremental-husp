#!/usr/bin/env python3
"""Run the two measurement-machine blocks of a runbook verbatim under PowerShell, with fake tools, on throw-away
git repositories, and check what they do.

    python3 tools/check_runbook_blocks.py <runbook.txt> <run-status report.ps1> <scratch dir> [--only main|followup|resume2|reserve]

The main block is the one whose header (within six lines above its "& {") names E2R; the follow-up block is the
one whose header names E2G; the pinned resume block is the one whose header names the pinned code "src a7bc247"; the reserve block is the one
whose header names bud3rs. Only machine-specific parts are replaced, and each replacement must
match exactly once: the D: paths, the run-status path, the GitHub URLs, and the kernel32 import (a stub type records
the keep-awake calls). In the git wrapper, backslashes in arguments become slashes, since the fakes run on macOS or
Linux. java, javac, py and Get-Process are PowerShell functions that record their calls.

Main block scenarios: the machine's state after a first paste whose commit failed (file staged, no git identity),
a second paste, a failing E2 run, failing tests, no identity anywhere. Follow-up block scenarios: a normal run, a
second paste, a java process still running, a merge conflict. Reserve block scenarios: a normal run, a second paste,
a java process still running, failing tests. Pinned resume block scenarios: a normal run that must not take the newer
branch, code that differs from the pinned code, a java process still running, tests recorded on other code, a stale
index lock, an unreachable remote. Exit status 1 when a check fails. Needs pwsh and git.
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

GENV = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_NOSYSTEM="1")
STUB = ("Add-Type -TypeDefinition 'namespace Power { public static class Awake { public static "
        "System.Collections.Generic.List<uint> Calls = new System.Collections.Generic.List<uint>(); public static uint "
        "SetThreadExecutionState(uint f) { Calls.Add(f); return 0; } } }'")
KERNEL32 = ("Add-Type -Namespace Power -Name Awake -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern "
            "uint SetThreadExecutionState(uint esFlags);'")
AWAKE_OK = "AWAKE 2147483649,2147483648"

PRELUDE = r'''
$realgit = (Get-Command git -CommandType Application | Select-Object -First 1).Source
function git { $a = @($args | ForEach-Object { if ($_ -is [string] -and -not $_.StartsWith('-') -and $_ -notmatch '^HEAD:') { $_ -replace '\\','/' } else { $_ } }); & $realgit @a }
function Get-Process { if ($env:FAKE_JAVA -eq '1') { [pscustomobject]@{ Name = 'java' } } }
function java { "    java.runtime.version = 25.0.5+8-LTS"; "    java.vendor = Eclipse Adoptium" }
function javac { Add-Content $env:CALLS ("javac " + ($args -join ' ')); $global:LASTEXITCODE = 0 }
function py {
  $tool = ($args[1] -replace '\\','/'); $rest = @($args | Select-Object -Skip 2 | ForEach-Object { $_ -replace '\\','/' })
  Add-Content $env:CALLS ("py " + $tool + " " + ($rest -join ' '))
  $code = 0
  switch -Wildcard ($tool) {
    '*machine_info.py' { Set-Content MEASUREMENT_MACHINE.json '{"hostname": "MEASURE-HOST"}' }
    '*run_tests.py' { $code = [int]$env:FAIL_TESTS }
    '*campaign.py' {
      if ($rest -contains 'lock') { New-Item -ItemType Directory -Force results/9950x | Out-Null; if (-not (Test-Path results/9950x/ENV_LOCK.json)) { Set-Content results/9950x/ENV_LOCK.json '{}' } }
      if ($rest -contains 'run' -and $rest -contains $env:FAIL_EXP) { $code = 1 }
    }
    '*summarize_campaign.py' {
      New-Item -ItemType Directory -Force results/9950x | Out-Null
      if ($rest -contains 'E2F') { Set-Content results/9950x/summary_E2F.tsv 'x'; "P1 bud3fs: 0 disagreeing" }
      elseif ($rest -contains '--exp') { Set-Content results/9950x/summary_E2L_E2N_E2G-raw.tsv 'x'; "P1: 0 disagreeing" }
      else { Set-Content results/9950x/summary_E2.tsv 'x'; Set-Content experiments/campaign_9950x.json '{"experiments": {"E2R": {}}}' }
      $code = 1
    }
    '*summarize_heap_guard.py' { "dataset`taborts_bud3"; "G1 ok: 3 bud3 aborts"; $code = 1 }
    '*summarize_reserve.py' {
      New-Item -ItemType Directory -Force results/9950x | Out-Null
      Set-Content results/9950x/summary_E2S.tsv 'x'
      if ($rest -contains '--write-repeats') { Set-Content experiments/campaign_9950x_reserve.json '{"experiments": {"E2S": {}, "E2SR": {}}}' }
      "D1: 0 queries disagreeing"; $code = 1
    }
  }
  $global:LASTEXITCODE = $code
}
'''


def git(*a, cwd=None, check=True):
    r = subprocess.run(["git", *a], cwd=cwd, capture_output=True, text=True, env=GENV)
    if check and r.returncode:
        raise SystemExit("setup git %s: %s" % (a, r.stderr))
    return r.stdout.strip()


def ok_git(*a, cwd=None):
    return subprocess.run(["git", *a], cwd=cwd, capture_output=True, env=GENV).returncode == 0


def find_block(lines, marker):
    for i, l in enumerate(lines):
        if l == "& {" and marker in "\n".join(lines[max(0, i - 6):i]):
            end = next(j for j in range(i + 1, len(lines)) if lines[j] == "}")
            return "\n".join(lines[i:end + 1])
    raise SystemExit("no block with %r in its header" % marker)


def seed_repos(T, report, followup, reserve=False):
    seed = T / "seed"
    seed.mkdir()
    files = {"src/main/java/A.java": "class A {}\n", "tools/stream_datasets/manifests/X.json": "{}\n", "tools/t.py": "\n",
             "experiments/campaign_9950x.json": '{"experiments": {}}\n',
             ".gitignore": "out/\nresults/*\n!results/9950x/\n*.log\n"}
    for p, c in files.items():
        (seed / p).parent.mkdir(parents=True, exist_ok=True)
        (seed / p).write_text(c)
    git("init", "-q", "-b", "revision-apin", cwd=seed)
    git("add", "-A", cwd=seed)
    git("-c", "user.name=S", "-c", "user.email=s@x", "commit", "-q", "-m", "seed", cwd=seed)
    if followup:
        git("switch", "-q", "-c", "heap-guard-live", cwd=seed)
        (seed / "experiments/campaign_9950x_followup.json").write_text('{"experiments": {"E2G-raw": {}}}\n')
        (seed / "src/main/java/A.java").write_text("class A { int live; }\n")
        (seed / "tools/stream_datasets/manifests/Y.json").write_text("{}\n")
        if reserve:
            (seed / "experiments/campaign_9950x_reserve.json").write_text('{"experiments": {"E2S": {}}}\n')
            (seed / "tools/summarize_reserve.py").write_text("\n")
        git("add", "-A", cwd=seed)
        git("-c", "user.name=S", "-c", "user.email=s@x", "commit", "-q", "-m", "follow-up", cwd=seed)
    git("clone", "-q", "--bare", str(seed), str(T / "remote.git"))
    rseed = T / "rseed"
    rseed.mkdir()
    shutil.copy(report, rseed / "report.ps1")
    git("init", "-q", "-b", "main", cwd=rseed)
    git("add", "-A", cwd=rseed)
    git("-c", "user.name=S", "-c", "user.email=s@x", "commit", "-q", "-m", "seed", cwd=rseed)
    git("clone", "-q", "--bare", str(rseed), str(T / "rs-remote.git"))
    git("clone", "-q", str(T / "rs-remote.git"), str(T / "rs"))
    (T / "lib" / "01_BanLe_GioHang").mkdir(parents=True)
    (T / "work").mkdir()


def run_block(T, block, subs, env_extra):
    b = block
    for old, new in subs:
        if b.count(old) != 1:
            raise SystemExit("replacement %r matched %d times, expected 1" % (old[:70], b.count(old)))
        b = b.replace(old, new)
    (T / "block.ps1").write_text(PRELUDE + b + '\n"AWAKE " + ([Power.Awake]::Calls -join ",")\n', encoding="utf-8")
    calls = T / "calls.txt"
    if calls.exists():
        calls.unlink()
    env = dict(GENV, CALLS=str(calls), FAIL_TESTS="0", FAIL_EXP="", FAKE_JAVA="0")
    env.update(env_extra)
    r = subprocess.run(["pwsh", "-NoProfile", "-File", str(T / "block.ps1")], cwd=str(T), capture_output=True, text=True,
                       env=env, timeout=600)
    (T / "out.txt").write_text(r.stdout + r.stderr)
    log = next((T / "rs").rglob("*.log"), None)
    return r, (calls.read_text().splitlines() if calls.exists() else []), (log.read_text().splitlines() if log else [])


def main_subs(T):
    return [('"D:\\work\\parallel-regular-incremental-husp"', '"%s/work/parallel-regular-incremental-husp"' % T),
            ('"D:\\ThuVienDuLieu"', '"%s/lib"' % T), ('"$HOME\\work\\run-status"', '"%s/rs"' % T),
            ('"D:\\"', '"%s/"' % T), ('"D:\\work"', '"%s/work"' % T),
            ("https://github.com/tran-minh-thai/run-status.git", "%s/rs-remote.git" % T),
            ("https://github.com/tran-minh-thai/parallel-regular-incremental-husp.git", "%s/remote.git" % T),
            (KERNEL32, STUB)]


def followup_subs(T):
    return [('"D:\\work\\parallel-regular-incremental-husp"', '"%s/work/parallel-regular-incremental-husp"' % T),
            ('"D:\\ThuVienDuLieu"', '"%s/lib"' % T), ('"$HOME\\work\\run-status"', '"%s/rs"' % T), (KERNEL32, STUB)]


def reserve_subs(T):
    return [('"D:\\work\\parallel-regular-incremental-husp"', '"%s/work/parallel-regular-incremental-husp"' % T),
            ('"$HOME\\work\\run-status"', '"%s/rs"' % T), (KERNEL32, STUB)]


def remote_files(T, ref):
    r = subprocess.run(["git", "--git-dir", str(T / "remote.git"), "ls-tree", "-r", "--name-only", ref],
                       capture_output=True, text=True, env=GENV)
    return set(r.stdout.split()) if r.returncode == 0 else set()


def check_main(block, report, base, check):
    def fresh(name, rs_identity=True, existing_clone=False):
        T = base / name
        if T.exists():
            shutil.rmtree(T)
        T.mkdir(parents=True)
        seed_repos(T, report, followup=False)
        if rs_identity:
            git("config", "user.name", "Test Operator", cwd=T / "rs")
            git("config", "user.email", "operator@example.org", cwd=T / "rs")
        if existing_clone:
            c = T / "work" / "parallel-regular-incremental-husp"
            git("clone", "-q", "-b", "revision-apin", str(T / "remote.git"), str(c))
            (c / "MEASUREMENT_MACHINE.json").write_text('{"hostname": "MEASURE-HOST"}\n')
            git("add", "MEASUREMENT_MACHINE.json", cwd=c)
        return T

    T = fresh("main_A", existing_clone=True)
    c = T / "work" / "parallel-regular-incremental-husp"
    r, calls, log = run_block(T, block, main_subs(T), {})
    check(git("config", "user.name", cwd=c, check=False) == "Test Operator", "main A: git identity copied from run-status")
    check(any("all steps finished" in l for l in log) and not any("STOPPED" in l for l in log), "main A: every step ran")
    check({"MEASUREMENT_MACHINE.json", "results/9950x/ENV_LOCK.json", "results/9950x/summary_E2.tsv"}
          <= remote_files(T, "refs/heads/campaign-9950x"), "main A: machine file, lock, summary on campaign-9950x")
    check("MEASUREMENT_MACHINE.json" not in remote_files(T, "refs/heads/revision-apin"), "main A: revision-apin untouched")
    exps = [l.split("--exp ")[1] for l in calls if "campaign.py run" in l]
    check(exps == ["E2", "E2K", "E2R", "E5"], "main A: campaign order %s" % exps)
    check(sum("run_tests.py" in l for l in calls) == 1 and sum("build_all_datasets.py" in l for l in calls) == 2
          and sum("fetch_datasets.py --library" in l for l in calls) == 1, "main A: tests once, builder verify and build, fetch")
    check(AWAKE_OK in r.stdout, "main A: keep-awake set then released")
    check(any("repeats: exit 1" in l for l in log), "main A: repeats committed although the summary checks fail")
    check(git("status", "--porcelain", "--untracked-files=no", cwd=c, check=False) == "", "main A: tracked tree clean")
    n = git("rev-list", "--count", "HEAD", cwd=c)
    r, calls, log = run_block(T, block, main_subs(T), {})
    check(any("data: built and verified before" in l for l in log) and any("tests: passed before" in l for l in log)
          and not any(t in l for l in calls for t in ("machine_info", "run_tests", "build_all")), "main B: second paste skips")
    check(git("rev-list", "--count", "HEAD", cwd=c) == n, "main B: no new commit")
    T = fresh("main_C")
    r, calls, log = run_block(T, block, main_subs(T), {"FAIL_EXP": "E2"})
    exps = [l.split("--exp ")[1] for l in calls if "campaign.py run" in l]
    check(any("campaign E2: exit 1" in l for l in log) and exps == ["E2", "E2K", "E2R", "E5"], "main C: failing E2, later steps run")
    T = fresh("main_D")
    r, calls, log = run_block(T, block, main_subs(T), {"FAIL_TESTS": "1"})
    check(any("STOPPED at tests" in l for l in log) and not any("campaign.py" in l for l in calls) and AWAKE_OK in r.stdout,
          "main D: failing tests stop before lock and campaign, keep-awake released")
    T = fresh("main_E", rs_identity=False)
    r, calls, log = run_block(T, block, main_subs(T), {})
    check(any("FAILED" in l and "user.name" in l for l in log) and not any(l.startswith("py ") for l in calls),
          "main E: no identity stops at setup")


def check_followup(block, report, base, check):
    def fresh(name, conflict=False):
        T = base / name
        if T.exists():
            shutil.rmtree(T)
        T.mkdir(parents=True)
        seed_repos(T, report, followup=True)
        c = T / "work" / "parallel-regular-incremental-husp"
        git("clone", "-q", "-b", "revision-apin", str(T / "remote.git"), str(c))
        for d in (c, T / "rs"):
            git("config", "user.name", "Test Operator", cwd=d)
            git("config", "user.email", "operator@example.org", cwd=d)
        (c / "results/9950x").mkdir(parents=True)
        (c / "results/9950x/cells.tsv").write_text("key\nE2|x\n")
        if conflict:
            (c / "experiments/campaign_9950x_followup.json").write_text('{"other": 1}\n')
        git("add", "-A", cwd=c)
        git("commit", "-q", "-m", "campaign results", cwd=c)
        return T, c

    T, c = fresh("followup_A")
    r, calls, log = run_block(T, block, followup_subs(T), {})
    check(ok_git("merge-base", "--is-ancestor", "origin/heap-guard-live", "HEAD", cwd=c), "followup A: heap-guard-live merged")
    data = [l for l in calls if "build_all_datasets.py" in l]
    check(data == ["py tools/build_all_datasets.py %s/lib --only VS-CHAIN152-CW-NOACC,VS-CHAIN46-CW-NOACC" % T],
          "followup A: filtered datasets built from the library %s" % data)
    camp = [l for l in calls if "tools/campaign.py" in l]
    check(camp == ["py tools/campaign.py run --exp E2K", "py tools/campaign.py run --exp E2R", "py tools/campaign.py run --exp E5",
                   "py tools/campaign.py --config experiments/campaign_9950x_followup.json run --exp E2G-raw --exp E2G-live "
                   "--exp E2L --exp E2N --exp E2F --interleave"], "followup A: rest of the main campaign, then the follow-up %s" % camp)
    check(any("repeats: exit 1" in l for l in log) and "results/9950x/summary_E2.tsv" in remote_files(T, "refs/heads/campaign-9950x"),
          "followup A: repeats generated and committed before E2R")
    check(sum("run_tests.py" in l for l in calls) == 1, "followup A: tests run once on the merged code")
    files = remote_files(T, "refs/heads/campaign-9950x")
    check({"results/9950x/summary_E2G.txt", "results/9950x/summary_followup.txt", "results/9950x/summary_E2L_E2N_E2G-raw.tsv",
           "results/9950x/summary_E2F.txt", "results/9950x/summary_E2F.tsv",
           "experiments/campaign_9950x_followup.json"} <= files, "followup A: merge and the three summaries on campaign-9950x")
    s = (c / "results/9950x/summary_E2G.txt").read_bytes()
    check(b"G1 ok" in s and not s.startswith(b"\xef\xbb\xbf") and b"\x00" not in s, "followup A: summary is UTF-8 without BOM")
    check(any("resume summary: exit 1" in l for l in log) and any("resume finished" in l for l in log),
          "followup A: failing summary checks reported, block finished")
    check(AWAKE_OK in r.stdout, "followup A: keep-awake set then released")
    n = git("rev-list", "--count", "HEAD", cwd=c)
    r, calls, log = run_block(T, block, followup_subs(T), {})
    check(any("resume tests: passed before" in l for l in log) and any("resume data: built and verified before" in l for l in log)
          and not any("run_tests" in l or "build_all" in l for l in calls), "followup B: second paste skips data and tests")
    check(git("rev-list", "--count", "HEAD", cwd=c) == n, "followup B: no new commit")
    T, c = fresh("followup_C")
    r, calls, log = run_block(T, block, followup_subs(T), {"FAKE_JAVA": "1"})
    check(any("STOPPED at resume setup" in l for l in log) and not calls
          and not ok_git("merge-base", "--is-ancestor", "origin/heap-guard-live", "HEAD", cwd=c), "followup C: running java stops it")
    T, c = fresh("followup_D", conflict=True)
    r, calls, log = run_block(T, block, followup_subs(T), {})
    check(any("STOPPED at resume setup" in l for l in log) and not (c / ".git" / "MERGE_HEAD").exists()
          and git("status", "--porcelain", "--untracked-files=no", cwd=c, check=False) == "" and not calls,
          "followup D: conflicting merge aborted, tree clean, nothing runs")


def check_resume2(block, report, base, check):
    """The machine after the follow-up block was interrupted: follow-up merged, tests passed on that code, one cell done;
    heap-guard-live has since moved on. The block must run the rest on the same code without merging."""
    pinned = "a7bc2475473ddbe0eb4441d77665278cedbe18f7 d158eb6c15758dfb3f6721bf6f5b7b14c7042074"

    def fresh(name, merge_newer=False, tested=None, lock=False, offline=False):
        T = base / name
        if T.exists():
            shutil.rmtree(T)
        T.mkdir(parents=True)
        seed_repos(T, report, followup=True)
        c = T / "work" / "parallel-regular-incremental-husp"
        git("clone", "-q", "-b", "revision-apin", str(T / "remote.git"), str(c))
        for d in (c, T / "rs"):
            git("config", "user.name", "Test Operator", cwd=d)
            git("config", "user.email", "operator@example.org", cwd=d)
        git("merge", "-q", "--no-edit", "origin/heap-guard-live", cwd=c)
        key = git("rev-parse", "HEAD:src", cwd=c) + " " + git("rev-parse", "HEAD:tools", cwd=c)
        (c / "results/9950x").mkdir(parents=True)
        (c / "results/9950x/cells.tsv").write_text("key\nE2G-raw|x\n")
        (c / "out").mkdir()
        (c / "out/tests_ok.txt").write_text((tested or key) + "\n")
        git("add", "-A", cwd=c)
        git("commit", "-q", "-m", "campaign results", cwd=c)
        seed = T / "seed"                                       # the branch moves on after the interruption
        (seed / "src/main/java/A.java").write_text("class A { int live; int reserve; }\n")
        git("add", "-A", cwd=seed)
        git("-c", "user.name=S", "-c", "user.email=s@x", "commit", "-q", "-m", "reserve", cwd=seed)
        git("push", "-q", str(T / "remote.git"), "heap-guard-live", cwd=seed)
        git("fetch", "-q", "origin", cwd=c)
        if merge_newer:
            git("merge", "-q", "--no-edit", "origin/heap-guard-live", cwd=c)
        if lock:
            (c / ".git/index.lock").write_text("")
        if offline:
            git("remote", "set-url", "origin", str(T / "missing.git"), cwd=c)
        return T, c, key

    def subs(T, key):
        return reserve_subs(T) + [(pinned, key)]

    cfg = "experiments/campaign_9950x_followup.json"
    T, c, key = fresh("resume2_A")
    r, calls, log = run_block(T, block, subs(T, key), {})
    check(not ok_git("merge-base", "--is-ancestor", "origin/heap-guard-live", "HEAD", cwd=c)
          and git("rev-parse", "HEAD:src", cwd=c) + " " + git("rev-parse", "HEAD:tools", cwd=c) == key,
          "resume2 A: newer heap-guard-live not merged, code unchanged")
    camp = [l for l in calls if "tools/campaign.py" in l]
    check(camp == ["py tools/campaign.py --config %s run --exp E2G-raw --exp E2G-live --exp E2L --exp E2N --exp E2F --interleave" % cfg],
          "resume2 A: only the follow-up campaign runs %s" % camp)
    check(not any("run_tests.py" in l or "build_all" in l for l in calls) and sum(l.startswith("javac") for l in calls) == 1,
          "resume2 A: no tests, no dataset build, one compile")
    files = remote_files(T, "refs/heads/campaign-9950x")
    check({"results/9950x/summary_E2G.txt", "results/9950x/summary_followup.txt", "results/9950x/summary_E2F.txt",
           "results/9950x/summary_E2L_E2N_E2G-raw.tsv", "results/9950x/summary_E2F.tsv"} <= files,
          "resume2 A: the three summaries on campaign-9950x")
    check(any("resume summary: exit 1" in l for l in log) and any("resume finished" in l for l in log)
          and not any("STOPPED" in l for l in log), "resume2 A: failing summary checks reported, block finished")
    check(AWAKE_OK in r.stdout, "resume2 A: keep-awake set then released")
    check(git("status", "--porcelain", "--untracked-files=no", cwd=c, check=False) == "", "resume2 A: tracked tree clean")
    n = git("rev-list", "--count", "HEAD", cwd=c)
    r, calls, log = run_block(T, block, subs(T, key), {})
    check(any("resume finished" in l for l in log) and git("rev-list", "--count", "HEAD", cwd=c) == n,
          "resume2 B: second paste finishes without a new commit")
    T, c, key = fresh("resume2_C", merge_newer=True)
    r, calls, log = run_block(T, block, subs(T, key), {})
    check(any("STOPPED at resume2 setup" in l for l in log) and any("FAILED" in l and key in l for l in log) and not calls,
          "resume2 C: code other than the pinned code stops before anything runs")
    T, c, key = fresh("resume2_D")
    r, calls, log = run_block(T, block, subs(T, key), {"FAKE_JAVA": "1"})
    check(any("STOPPED at resume2 setup" in l for l in log) and not calls and AWAKE_OK in r.stdout,
          "resume2 D: running java stops it, keep-awake released")
    T, c, key = fresh("resume2_E", tested="key of other code")
    r, calls, log = run_block(T, block, subs(T, key), {})
    check(any("STOPPED at resume2 setup" in l for l in log) and any("tests_ok.txt" in l for l in log) and not calls,
          "resume2 E: tests recorded on other code stop it")
    T, c, key = fresh("resume2_F", lock=True)
    r, calls, log = run_block(T, block, subs(T, key), {})
    check(not (c / ".git/index.lock").exists() and any("resume finished" in l for l in log)
          and "results/9950x/summary_E2G.txt" in git("ls-tree", "-r", "--name-only", "HEAD", cwd=c).split(),
          "resume2 F: stale index lock removed, block finished and committed")
    T, c, key = fresh("resume2_G", offline=True)
    r, calls, log = run_block(T, block, subs(T, key), {})
    check(any("resume finished" in l for l in log) and any("resume summary: exit" in l for l in log)
          and len([l for l in calls if "tools/campaign.py" in l]) == 1
          and "results/9950x/summary_E2G.txt" in git("ls-tree", "-r", "--name-only", "HEAD", cwd=c).split()
          and "WARNING" in (T / "out.txt").read_text() and "campaign-9950x" in (T / "out.txt").read_text(),
          "resume2 G: unreachable remote only warns; summaries committed locally, block finished")


def check_reserve_block(block, report, base, check):
    def fresh(name):
        T = base / name
        if T.exists():
            shutil.rmtree(T)
        T.mkdir(parents=True)
        seed_repos(T, report, followup=True, reserve=True)
        c = T / "work" / "parallel-regular-incremental-husp"
        git("clone", "-q", "-b", "revision-apin", str(T / "remote.git"), str(c))
        for d in (c, T / "rs"):
            git("config", "user.name", "Test Operator", cwd=d)
            git("config", "user.email", "operator@example.org", cwd=d)
        (c / "results/9950x").mkdir(parents=True)
        (c / "results/9950x/cells.tsv").write_text("key\nE2|x\n")
        (c / "out").mkdir()
        (c / "out/tests_ok.txt").write_text("key of the code before the merge\n")
        git("add", "-A", cwd=c)
        git("commit", "-q", "-m", "campaign results", cwd=c)
        return T, c

    cfg = "experiments/campaign_9950x_reserve.json"
    T, c = fresh("reserve_A")
    r, calls, log = run_block(T, block, reserve_subs(T), {})
    check(ok_git("merge-base", "--is-ancestor", "origin/heap-guard-live", "HEAD", cwd=c), "reserve A: heap-guard-live merged")
    check(sum("run_tests.py" in l for l in calls) == 1, "reserve A: tests run once on the merged code")
    camp = [l for l in calls if "tools/campaign.py" in l]
    check(camp == ["py tools/campaign.py --config %s run --exp E2S-S1c --exp E2Sc --exp E2SO --interleave" % cfg,
                   "py tools/campaign.py --config %s run --exp E2S-S1 --exp E2S --interleave" % cfg,
                   "py tools/campaign.py --config %s run --exp E2SR" % cfg],
          "reserve A: cheap pairs, long pairs, then the generated repeats %s" % camp)
    summ = [l for l in calls if "summarize_reserve.py" in l]
    check(summ == ["py tools/summarize_reserve.py --config %s --write-repeats" % cfg, "py tools/summarize_reserve.py --config %s" % cfg],
          "reserve A: repeats generated, then the summary %s" % summ)
    files = remote_files(T, "refs/heads/campaign-9950x")
    check({cfg, "results/9950x/summary_E2S.txt", "results/9950x/summary_E2S.tsv"} <= files,
          "reserve A: generated repeats and the summary on campaign-9950x")
    s = (c / "results/9950x/summary_E2S.txt").read_bytes()
    check(b"D1:" in s and not s.startswith(b"\xef\xbb\xbf") and b"\x00" not in s, "reserve A: summary is UTF-8 without BOM")
    check(any("reserve repeats: exit 1" in l for l in log) and any("reserve summary: exit 1" in l for l in log)
          and any("reserve finished" in l for l in log) and not any("STOPPED" in l for l in log),
          "reserve A: failing hypotheses reported, block finished")
    check(AWAKE_OK in r.stdout, "reserve A: keep-awake set then released")
    check(git("status", "--porcelain", "--untracked-files=no", cwd=c, check=False) == "", "reserve A: tracked tree clean")
    n = git("rev-list", "--count", "HEAD", cwd=c)
    r, calls, log = run_block(T, block, reserve_subs(T), {})
    check(any("reserve tests: passed before" in l for l in log) and not any("run_tests" in l for l in calls),
          "reserve B: second paste skips the tests")
    check(git("rev-list", "--count", "HEAD", cwd=c) == n, "reserve B: no new commit")
    T, c = fresh("reserve_C")
    r, calls, log = run_block(T, block, reserve_subs(T), {"FAKE_JAVA": "1"})
    check(any("STOPPED at reserve setup" in l for l in log) and not calls
          and not ok_git("merge-base", "--is-ancestor", "origin/heap-guard-live", "HEAD", cwd=c), "reserve C: running java stops it")
    T, c = fresh("reserve_D")
    r, calls, log = run_block(T, block, reserve_subs(T), {"FAIL_TESTS": "1"})
    check(any("STOPPED at reserve tests" in l for l in log) and not any("campaign.py" in l or "summarize" in l for l in calls)
          and AWAKE_OK in r.stdout, "reserve D: failing tests stop before the campaign, keep-awake released")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("runbook")
    ap.add_argument("report")
    ap.add_argument("scratch")
    ap.add_argument("--only", choices=("main", "followup", "resume2", "reserve"))
    args = ap.parse_args(argv)
    lines = Path(args.runbook).read_text(encoding="utf-8").splitlines()
    base = Path(args.scratch).resolve()
    results = []

    def check(ok, what):
        results.append((ok, what))
        print(("ok    " if ok else "FAIL  ") + what, flush=True)

    if args.only in (None, "main"):
        check_main(find_block(lines, "E2R"), Path(args.report), base, check)
    if args.only in (None, "followup"):
        check_followup(find_block(lines, "E2G"), Path(args.report), base, check)
    if args.only in (None, "resume2"):
        check_resume2(find_block(lines, "src a7bc247"), Path(args.report), base, check)
    if args.only in (None, "reserve"):
        check_reserve_block(find_block(lines, "bud3rs"), Path(args.report), base, check)
    bad = [w for ok, w in results if not ok]
    print("\n%d/%d checks passed" % (len(results) - len(bad), len(results)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
