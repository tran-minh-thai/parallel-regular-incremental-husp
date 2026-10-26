#!/usr/bin/env python3
"""Summarise the phase profiles written by QueryScheduleProbe under -Dprof=true.

For each run directory (results-probe/<stamp>_<dataset>/profile_<policy>.txt) prints, per policy:
wall time of every phase, its share of the profiled total, and cores busy (Java-thread CPU / wall).
Serial phases are the ones whose cores stay near 1 at every thread count. Phases marked wallOnly span
a pool whose threads die inside them, so their CPU is not readable.

Usage: python3 tools/analyze_parallel_profile.py results-probe RUN_DIR_SUBSTRING [...]
"""
import glob
import json
import os
import re
import sys


def main():
    root, keys = sys.argv[1], sys.argv[2:]
    for d in sorted(glob.glob(os.path.join(root, "*"))):
        if not os.path.isdir(d) or (keys and not any(k in d for k in keys)):
            continue
        profs = sorted(glob.glob(os.path.join(d, "profile_*.txt")))
        if not profs:
            continue
        cmd = json.load(open(os.path.join(d, "run_meta.json")))["command"]
        thr = re.search(r"-Dthreads=(\d+)", cmd)
        print("== %s threads=%s" % (os.path.basename(d), thr.group(1) if thr else "?"))
        for p in profs:
            lines = open(p).read().splitlines()
            pol = lines[0]
            rows = []
            for ln in lines[2:]:
                f = ln.split()
                if len(f) < 6:
                    continue
                rows.append((f[0], int(f[1]), float(f[2]), f[4]))
            tot = sum(w for n, c, w, _ in rows if n in ("engine.load", "engine.poolCreate", "engine.tier0.scan",
                      "engine.tier0.merge", "engine.eucs.scan", "engine.eucs.merge", "engine.eucs.sort",
                      "engine.mine", "engine.poolShutdown.wallOnly")
                      or n.startswith("maint.") or n in ("disc.knownAndParse", "disc.invertedIndex", "disc.rebuildMatch"))
            print("   %s  profiled_total=%.0f ms" % (pol, tot))
            for n, c, w, cores in rows:
                print("     %-28s calls=%-5d wall=%10.1f ms  share=%5.1f%%  cores=%s" % (n, c, w, 100 * w / tot if tot else 0, cores))


if __name__ == "__main__":
    main()
