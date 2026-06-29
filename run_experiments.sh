#!/usr/bin/env bash
#
# run_experiments.sh - Compile and run a P-RIncHUSP experiment (macOS/Linux).
#
# Default (no args) runs the full official benchmark suite (S1-S4) on every
# dataset declared in DatasetCatalog.officialSuite().
#
# Pipeline:
#   1. mvn -q compile                                       (skip with --skip-build)
#   2. mvn -q exec:exec -Dexec.mainClass=<class>
#                       -DheapSize=<heap>
#                       -DstackSize=<stack>
#   3. Tee stdout/stderr to results/run_log_<experiment>_<timestamp>.txt
#   4. Print elapsed time + any new file(s) in results/.
#
# Usage:
#   ./run_experiments.sh                         # full suite, 16g heap, 4m stack
#   ./run_experiments.sh -e recall               # RecallProbe
#   ./run_experiments.sh -e mu --heap 8g         # MuProbe, 8 GB heap
#   ./run_experiments.sh --skip-build --dry-run  # show what would run
#   ./run_experiments.sh -h                      # show this help

set -euo pipefail

# ---------- Defaults ----------
EXPERIMENT="all"
HEAP="16g"
STACK="4m"
SKIP_BUILD=0
DRY_RUN=0
NO_CAFFEINATE=0   # macOS only: prevent idle sleep during the run (auto-detected)

# ---------- Experiment -> Maven main class ----------
main_class_for() {
    case "$1" in
        all|official) echo "test.ExperimentOfficial" ;;
        test)         echo "test.ExperimentTest"     ;;
        perf)         echo "test.PerfProbe"          ;;
        recall)       echo "test.RecallProbe"        ;;
        mu)           echo "test.MuProbe"            ;;
        oracle)       echo "test.OracleValidation"   ;;
        *) echo "" ;;
    esac
}

# ---------- Help ----------
print_help() {
    cat <<'EOF'
Usage: ./run_experiments.sh [options]

Options:
  -e, --experiment <name>   Which experiment to run (default: all)
                              all | official  -> test.ExperimentOfficial   (full suite)
                              test            -> test.ExperimentTest       (saves pattern .txt)
                              perf            -> test.PerfProbe
                              recall          -> test.RecallProbe
                              mu              -> test.MuProbe
                              oracle          -> test.OracleValidation

      --heap   <size>       Max JVM heap, e.g. 8g, 16g (default 16g).
      --stack  <size>       Per-thread stack, e.g. 4m, 8m (default 4m).
      --skip-build          Skip `mvn compile` (assumes target/classes is up to date).
      --no-caffeinate       (macOS) do NOT wrap mvn with `caffeinate -i`.
                            By default, on macOS the run is wrapped in
                            `caffeinate -i` so the Mac stays awake until
                            the experiment finishes.
      --dry-run             Print commands but do not execute.
  -h, --help                Show this help.
EOF
}

# ---------- Parse args ----------
while [[ $# -gt 0 ]]; do
    case "$1" in
        -e|--experiment) EXPERIMENT="${2:?missing value for $1}"; shift 2 ;;
        --heap)          HEAP="${2:?missing value for $1}"; shift 2 ;;
        --stack)         STACK="${2:?missing value for $1}"; shift 2 ;;
        --skip-build)    SKIP_BUILD=1; shift ;;
        --no-caffeinate) NO_CAFFEINATE=1; shift ;;
        --dry-run)       DRY_RUN=1; shift ;;
        -h|--help)       print_help; exit 0 ;;
        *) echo "Unknown option: $1" >&2; print_help; exit 2 ;;
    esac
done

# Normalize experiment name and resolve main class
EXP_KEY="$(echo "$EXPERIMENT" | tr '[:upper:]' '[:lower:]')"
MAIN_CLASS="$(main_class_for "$EXP_KEY")"
if [[ -z "$MAIN_CLASS" ]]; then
    echo "ERROR: unknown experiment '$EXPERIMENT'" >&2
    print_help
    exit 2
fi

# Move to project root (directory of this script)
PROJ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJ_ROOT"

RESULTS_DIR="$PROJ_ROOT/results"
mkdir -p "$RESULTS_DIR"
STAMP="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$RESULTS_DIR/run_log_${EXP_KEY}_${STAMP}.txt"

# ---------- Helpers ----------
section() { local line; line="$(printf '=%.0s' {1..72})"; printf '\n%s\n  %s\n%s\n' "$line" "$1" "$line"; }

# Cores (macOS uses sysctl, Linux uses nproc)
if command -v nproc >/dev/null 2>&1; then
    CORES="$(nproc)"
elif command -v sysctl >/dev/null 2>&1; then
    CORES="$(sysctl -n hw.logicalcpu)"
else
    CORES="?"
fi

# ---------- Sanity ----------
command -v mvn  >/dev/null 2>&1 || { echo "mvn not found on PATH"  >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "java not found on PATH" >&2; exit 1; }

# Wrap with caffeinate -i on macOS to prevent idle sleep during multi-hour runs.
# caffeinate auto-stops when the wrapped process exits; no manual cleanup needed.
RUNNER_PREFIX=()
CAFFEINATE_STATUS="off"
if [[ "$(uname -s)" == "Darwin" ]] && (( NO_CAFFEINATE == 0 )) && command -v caffeinate >/dev/null 2>&1; then
    RUNNER_PREFIX=(caffeinate -i)
    CAFFEINATE_STATUS="on (caffeinate -i)"
elif [[ "$(uname -s)" == "Darwin" ]] && (( NO_CAFFEINATE == 1 )); then
    CAFFEINATE_STATUS="off (--no-caffeinate)"
fi

section "P-RIncHUSP experiment runner (Maven)"
echo "Project root  : $PROJ_ROOT"
echo "Experiment    : $EXPERIMENT  ->  $MAIN_CLASS"
echo "Heap / Stack  : -Xmx${HEAP}  /  -Xss${STACK}"
echo "mvn / java    : $(command -v mvn)  /  $(command -v java)"
echo "Sleep guard   : $CAFFEINATE_STATUS"
echo "Log file      : $LOG_FILE"
echo "Cores         : $CORES"

# ---------- Step 1: compile ----------
if (( SKIP_BUILD == 0 )); then
    section "Step 1/2  mvn compile"
    echo "mvn -q compile"
    if (( DRY_RUN == 0 )); then
        mvn -q compile
    fi
    echo "Compile OK"
else
    section "Step 1/2  mvn compile  [SKIPPED]"
    if [[ ! -f "$PROJ_ROOT/target/classes/test/ExperimentOfficial.class" ]]; then
        echo "ERROR: target/classes does not contain compiled classes - rerun without --skip-build" >&2
        exit 1
    fi
fi

# ---------- Step 2: run ----------
section "Step 2/2  mvn exec:exec  ($MAIN_CLASS)"

# Snapshot of results/ before the run, so we can list newly created files later.
FILES_BEFORE="$(mktemp)"
trap 'rm -f "$FILES_BEFORE"' EXIT
find "$RESULTS_DIR" -type f 2>/dev/null | sort > "$FILES_BEFORE"

MVN_ARGS=(-q exec:exec
          "-Dexec.mainClass=$MAIN_CLASS"
          "-DheapSize=$HEAP"
          "-DstackSize=$STACK")
echo "${RUNNER_PREFIX[*]} mvn ${MVN_ARGS[*]}"
echo "Streaming output to console AND $LOG_FILE ..."
echo ""

START_TS=$(date +%s)
if (( DRY_RUN == 0 )); then
    # Tee combines stdout+stderr to console AND log file. PIPESTATUS preserves mvn's exit code.
    set +e
    # ${arr[@]+...} pattern: safe with empty array under `set -u` on macOS bash 3.2.
    ${RUNNER_PREFIX[@]+"${RUNNER_PREFIX[@]}"} mvn "${MVN_ARGS[@]}" 2>&1 | tee "$LOG_FILE"
    EXIT=${PIPESTATUS[0]}
    set -e
else
    EXIT=0
fi
END_TS=$(date +%s)
ELAPSED=$(( END_TS - START_TS ))
H=$(( ELAPSED / 3600 )); M=$(( (ELAPSED % 3600) / 60 )); S=$(( ELAPSED % 60 ))

# ---------- Summary ----------
section "Done"
printf 'Elapsed      : %02d:%02d:%02d\n' "$H" "$M" "$S"
echo "Exit code    : $EXIT"
echo "Log file     : $LOG_FILE"

if (( EXIT == 0 )) && (( DRY_RUN == 0 )); then
    FILES_AFTER="$(mktemp)"
    find "$RESULTS_DIR" -type f 2>/dev/null | sort > "$FILES_AFTER"
    # Newly created files (excluding the log file itself)
    NEW="$(comm -13 "$FILES_BEFORE" "$FILES_AFTER" | grep -v "^$LOG_FILE$" || true)"
    rm -f "$FILES_AFTER"
    if [[ -n "$NEW" ]]; then
        echo "New file(s)  :"
        while IFS= read -r f; do echo "               $f"; done <<<"$NEW"
    else
        echo "No new file in results/ (probe-only experiments may print to console only)"
    fi
elif (( EXIT != 0 )); then
    echo "Run FAILED (exit $EXIT) - inspect $LOG_FILE" >&2
    exit "$EXIT"
fi
