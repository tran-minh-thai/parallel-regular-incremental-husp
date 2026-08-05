#!/usr/bin/env bash
#
# results_status.sh - Show every experiment run directory with its validity and config signature,
# so you can tell at a glance which numbers are complete + current, and which are stale/partial.
#
#   VALID    the run has a DONE marker  -> finished; results.csv is complete and usable.
#   PARTIAL  no DONE marker             -> aborted (crash/kill/OOM). Continue it with
#                                          ./run_experiments.sh --resume   (or delete the folder).
#
# SIGNATURE identifies the configuration (datasets + δ/ρ/s1Only, μ band, warm-up/measured runs,
# timeout, S1/S2/S4 switches). Runs with DIFFERENT signatures came from DIFFERENT configs,
# never mix their numbers in one analysis.

set -u

PROJ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$PROJ_ROOT/results"

if [[ ! -d "$RESULTS_DIR" ]]; then
    echo "No results/ directory yet; nothing has been run."
    exit 0
fi

shopt -s nullglob
runs=("$RESULTS_DIR"/run_*/)
if (( ${#runs[@]} == 0 )); then
    echo "No run directories in results/ yet."
    exit 0
fi

printf '%-30s  %-8s  %-9s  %9s  %s\n' "RUN DIRECTORY" "STATUS" "SIGNATURE" "OK/ERR" "STARTED"
printf '%s\n' "-------------------------------------------------------------------------------------------"

for d in "${runs[@]}"; do
    d="${d%/}"
    name="$(basename "$d")"
    meta="$d/meta.properties"

    if [[ -f "$d/DONE" ]]; then status="VALID"; else status="PARTIAL"; fi

    sig="?"; started="?"
    if [[ -f "$meta" ]]; then
        s="$(grep -m1 '^config\.signature8=' "$meta" 2>/dev/null | cut -d= -f2- || true)"
        [[ -n "${s:-}" ]] && sig="$s"
        # java.util.Properties escapes ':' as '\:'; strip the backslashes for display
        t="$(grep -m1 '^startTime=' "$meta" 2>/dev/null | cut -d= -f2- | tr -d '\\' || true)"
        [[ -n "${t:-}" ]] && started="$t"
    fi

    # Count OK vs failed (ERROR/TIMEOUT) result rows: a run can be VALID (DONE) yet mostly errors.
    ok=0; err=0
    if [[ -f "$d/results.csv" ]]; then
        ok=$(awk -F, 'NR>1 && $NF=="OK"'                 "$d/results.csv" | wc -l | tr -d ' ')
        err=$(awk -F, 'NR>1 && $NF!="OK" && $NF!=""'     "$d/results.csv" | wc -l | tr -d ' ')
    fi
    (( err > 0 )) && status="${status}*"

    printf '%-30s  %-8s  %-9s  %9s  %s\n' "$name" "$status" "$sig" "${ok}/${err}" "$started"
done

echo ""
echo "VALID   = has DONE (the suite finished).  PARTIAL = no DONE (aborted -> ./run_experiments.sh --resume)."
echo "OK/ERR  = so o (cell x iteration) THANH CONG / bi LOI (ERROR/TIMEOUT/OOM)."
echo "STATUS marked '*'  = the run finished but some cells errored -> check the OK/ERR columns before using its numbers"
echo "Different SIGNATURE = different CONFIGURATION -> never mix their numbers in one table."
