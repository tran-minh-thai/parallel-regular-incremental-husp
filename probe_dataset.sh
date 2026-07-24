#!/usr/bin/env bash
# Probe a new dataset before admitting it to the suite.
#
# Two questions have to be answered before any dataset is used, and in this order:
#
#   1. Which delta puts the pattern count in a workable band? Too few patterns and the
#      mining phase is dominated by loading; too many and the run never finishes.
#   2. Is the regularity constraint ACTUALLY ACTIVE at the rho we use? This is the one
#      that is easy to skip and expensive to get wrong. If the pattern count does not
#      move as rho tightens, the constraint is vacuous on that dataset and the numbers
#      describe plain high-utility mining, not the regular variant this work is about.
#
# MSNBC failed check 2: 196 patterns at rho = 0.01, 0.02, 0.05 and 0.30 alike. With 17
# distinct items over sequences averaging 13 events, every item recurs in nearly every
# sequence, so the real gaps are 1 to 3 while rho*N is already 318 at rho = 0.01.
#
# Usage:
#   ./probe_dataset.sh TAG                 # both sweeps, default ranges
#   ./probe_dataset.sh TAG "0.05 0.02"     # delta values to try
#   ./probe_dataset.sh TAG "0.02" "0.05 0.15 0.30"    # delta list, then rho list
#
# Run it on the benchmark machine. On a laptop with fewer cores the sweep is not slow,
# it is impractical: one E_SHOP configuration at delta = 0.05 did not finish in seven
# minutes at 700% CPU.
set -u

# Everything below is relative to the repository, so anchor to this script's own directory.
# That way the full path works from anywhere and there is no "cd first" step to forget.
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1

TAG="${1:?usage: ./probe_dataset.sh TAG [delta-list] [rho-list]}"
DELTAS="${2:-0.05 0.02 0.01 0.005}"
RHOS="${3:-0.05 0.15 0.30 0.60}"
# Half of physical RAM, capped at 24g to match the suite runs. Well below RAM on purpose: if the
# heap approaches it the JVM swaps instead of failing, which ruins every timing and can fill the
# disk. Too small is its own failure — a probe that dies of OutOfMemory says nothing about the
# threshold, only about the cap. Override with HEAP=.
_ram_gb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 8589934592) / 1073741824 ))
_half=$(( _ram_gb / 2 )); (( _half > 24 )) && _half=24; (( _half < 2 )) && _half=2
HEAP="${HEAP:-${_half}g}"
THREADS="${THREADS:-$(getconf _NPROCESSORS_ONLN)}"
# Per-configuration wall-clock limit, seconds. The sweep runs 8 configurations per dataset and the
# expensive end of a delta range can run for hours, so an unbounded probe is how an overnight run
# turns out in the morning to have spent itself on one cell. A configuration slower than this is
# too slow to use in the suite anyway: the full suite has 253 of them.
LIMIT="${LIMIT:-600}"

SEQ="datasets/${TAG}_seq.txt"
EUI="datasets/${TAG}_eui.txt"
for f in "$SEQ" "$EUI"; do
    [[ -f "$f" ]] || { echo "missing $f"; exit 1; }
done
[[ -f out/test/DeltaProbe.class ]] || {
    echo "building..."; rm -rf out && mkdir -p out
    javac --release 11 -d out $(find src/main/java -name '*.java') || exit 1
}

# macOS ships no timeout(1) — coreutils provides gtimeout, otherwise fall back to a watchdog.
probe() {  # delta rho -> one line
    local out; out=$(mktemp)
    java "-Xmx${HEAP}" -cp out test.DeltaProbe "$SEQ" "$EUI" "$1" "$2" "$THREADS" A >"$out" 2>&1 &
    local pid=$! waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if (( waited >= LIMIT )); then
            kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
            rm -f "$out"; echo "TIMEOUT after ${LIMIT}s — too slow for the suite"; return
        fi
        sleep 2; (( waited += 2 ))
    done
    wait "$pid" 2>/dev/null
    local line; line=$(grep -oE 'HS=[0-9]+ +SHS=[0-9]+ +peak=[0-9]+ MB +time=[0-9]+ ms' "$out")
    if [[ -n "$line" ]]; then echo "$line"
    elif grep -q 'OutOfMemoryError' "$out"; then echo "OUT OF MEMORY at heap $HEAP"
    else echo "no result — $(tail -1 "$out" | cut -c1-60)"
    fi
    rm -f "$out"
}

echo "=== $TAG: delta sweep at rho = 0.30, $THREADS threads, heap $HEAP ==="
for d in $DELTAS; do
    printf '  delta=%-7s %s\n' "$d" "$(probe "$d" 0.30)"
done

echo
echo "=== $TAG: is the regularity constraint active? ==="
echo "Pick the delta from above that gave a workable count, then pass it as argument 2."
BEST=$(echo $DELTAS | awk '{print $NF}')
for r in $RHOS; do
    printf '  rho=%-7s %s\n' "$r" "$(probe "$BEST" "$r")"
done
echo
echo "The pattern count MUST rise as rho loosens. A flat column means the constraint"
echo "never binds on this dataset, and the dataset does not belong in the suite."
