#!/usr/bin/env bash
#
# run_experiments.sh - Compile and run a P-RIncHUSP experiment (macOS/Linux).
#
# Default (no args) runs the full official benchmark suite (S1-S11) on every
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
RESUME=0          # --resume: continue the newest unfinished run (skip cells already done)
TEST_SUITE=0      # --test:   run the tiny testSuite (example datasets); seconds, for a smoke check
MODE1_ONLY=0      # --m1:     run only the per-batch-exact comparison (P-RIncHUSP-P vs ParRemine)
FOLLOWUP=0        # --followup: run all three follow-up probes in one session
ABSOLUTE=0        # --absolute: constant per-dataset regularity bound B = floor(rho*N_final)
NO_MAVEN=0        # --no-maven: build with javac + run with java (no Maven, no network)
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
      --skip-build          Skip the compile step (assumes classes are already built).
      --no-maven            Build with `javac` and run with `java` instead of Maven. The project has
                            NO external dependencies, so this needs no network and no ~/.m2; use it
                            when Maven cannot reach repo.maven.apache.org. Classes go to out/.
      --test                (full suite) Run the tiny testSuite (example datasets) instead of the real
                            one, finishing in seconds. Use it to smoke-test the WHOLE pipeline
                            (compile + run + results dir) before starting a multi-hour run.
      --resume              (full suite) Continue the newest UNFINISHED run: skip datasets/cells
                            already completed, redo only what is missing. Safe to re-invoke after a
                            crash/OOM/kill. A run is finished when its results/run_*/ has a DONE file.
      --m1                  (full suite) Run ONLY the per-batch-exact comparison: P-RIncHUSP-P
                            against ParRemine at each batch count. Both answer completely as each
                            batch lands, so this is the pair to compare when the application needs a
                            full result set after every batch rather than at query time. Writes one
                            scenario and leaves the reported suite untouched.
      --followup            (full suite) Run every follow-up probe in ONE session: the per-batch-exact
                            comparison, one full re-mine of D_new, and the lambda sweep under the
                            increasing distribution. Use this rather than three separate runs -- the
                            probes then share a machine and a warm-up and stay mutually comparable.
      --absolute            (full suite) Regularity as a DECLARED per-dataset constant B: a pattern
                            is regular when it recurs at least every B sequences, at every point in
                            time. B is a given number like delta, never derived from a database size.
                            The benchmark's B values are calibrated so final answers and the oracle
                            coincide with the relative suite's, keeping the two comparable. This is
                            the reformulated problem the manuscript pivots to.
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
        --resume)        RESUME=1; shift ;;
        --test)          TEST_SUITE=1; shift ;;
        --m1)            MODE1_ONLY=1; shift ;;
        --followup)      FOLLOWUP=1; shift ;;
        --absolute)      ABSOLUTE=1; shift ;;
        --no-maven)      NO_MAVEN=1; shift ;;
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
command -v java >/dev/null 2>&1 || { echo "java not found on PATH" >&2; exit 1; }
if (( NO_MAVEN == 1 )); then
    command -v javac >/dev/null 2>&1 || { echo "javac not found on PATH (need a JDK, not just a JRE)" >&2; exit 1; }
else
    command -v mvn >/dev/null 2>&1 || {
        echo "mvn not found on PATH. Tip: the project has no dependencies, so you can run without Maven:" >&2
        echo "      ./run_experiments.sh --no-maven" >&2
        exit 1
    }
fi

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

section "P-RIncHUSP experiment runner"
echo "Project root  : $PROJ_ROOT"
echo "Experiment    : $EXPERIMENT  ->  $MAIN_CLASS"
echo "Heap / Stack  : -Xmx${HEAP}  /  -Xss${STACK}"

# Refuse to start a measurement the machine cannot carry. The project is shared between two Macs
# through the same synced folder, so the checkout looks identical on both and only the hardware
# tells them apart. Asking for more heap than the box physically has means either the wrong machine
# or the wrong flag, and either way the numbers would not be comparable with the reported run.
# --dry-run and --test are exempt: both exist to exercise the pipeline anywhere.
if (( DRY_RUN == 0 && TEST_SUITE == 0 )) && [[ "$(uname -s)" == "Darwin" ]]; then
    heap_gb="${HEAP%[gG]}"
    if [[ "$heap_gb" =~ ^[0-9]+$ ]]; then
        ram_gb=$(( $(sysctl -n hw.memsize) / 1073741824 ))
        if (( heap_gb > ram_gb )); then
            echo
            echo "STOP: -Xmx${HEAP} exceeds this machine's ${ram_gb} GB of RAM."
            echo "      Host $(hostname -s), $(sysctl -n hw.ncpu) cores. The benchmark machine has 10 cores"
            echo "      and 32 GB; results from anywhere else do not compare with the reported run."
            echo "      Use --test to exercise the pipeline here, or run this on the benchmark machine."
            exit 3
        fi
    fi
fi
if (( NO_MAVEN == 1 )); then
    echo "Build / Run   : javac + java  (no Maven, no network needed)"
    echo "javac / java  : $(command -v javac)  /  $(command -v java)"
else
    echo "Build / Run   : Maven (mvn compile + exec:exec)"
    echo "mvn / java    : $(command -v mvn)  /  $(command -v java)"
fi
echo "Sleep guard   : $CAFFEINATE_STATUS"
echo "Log file      : $LOG_FILE"
echo "Cores         : $CORES"

# ---------- Step 1: compile ----------
# Where the .class files land: Maven -> target/classes ; javac fallback -> out/
if (( NO_MAVEN == 1 )); then
    CLASSES_DIR="$PROJ_ROOT/out"
else
    CLASSES_DIR="$PROJ_ROOT/target/classes"
fi

if (( SKIP_BUILD == 0 )); then
    if (( NO_MAVEN == 1 )); then
        section "Step 1/2  javac  (no Maven, no network)"
        echo "javac --release 11 -d '$CLASSES_DIR' <all .java under src/>"
        if (( DRY_RUN == 0 )); then
            mkdir -p "$CLASSES_DIR"
            # Collect sources NUL-safely: the project path may contain spaces (e.g. ".../My Drive/...").
            # An unquoted $(find ...) would word-split on those spaces and javac would reject the fragments.
            SRC_FILES=()
            while IFS= read -r -d '' f; do SRC_FILES+=("$f"); done \
                < <(find "$PROJ_ROOT/src" -name "*.java" -print0)
            if (( ${#SRC_FILES[@]} == 0 )); then
                echo "ERROR: no .java sources found under $PROJ_ROOT/src" >&2
                exit 1
            fi
            javac --release 11 -d "$CLASSES_DIR" "${SRC_FILES[@]}"
        fi
    else
        section "Step 1/2  mvn compile"
        echo "mvn -q compile"
        if (( DRY_RUN == 0 )); then
            mvn -q compile
        fi
    fi
    echo "Compile OK"
else
    section "Step 1/2  compile  [SKIPPED]"
    if [[ ! -f "$CLASSES_DIR/test/ExperimentOfficial.class" ]]; then
        echo "ERROR: $CLASSES_DIR has no compiled classes - rerun without --skip-build" >&2
        exit 1
    fi
fi

# ---------- Step 2: run ----------
if (( NO_MAVEN == 1 )); then
    section "Step 2/2  java  ($MAIN_CLASS)"
else
    section "Step 2/2  mvn exec:exec  ($MAIN_CLASS)"
fi

# Snapshot of results/ before the run, so we can list newly created files later.
FILES_BEFORE="$(mktemp)"
trap 'rm -f "$FILES_BEFORE"' EXIT
find "$RESULTS_DIR" -type f 2>/dev/null | sort > "$FILES_BEFORE"

# --resume / --test are only meaningful for the full-suite runner.
SUITE_MODE=0
[[ "$EXP_KEY" == "all" || "$EXP_KEY" == "official" ]] && SUITE_MODE=1

PROG_ARGS=()
HAVE_ARGS=0
if (( TEST_SUITE == 1 )); then
    if (( SUITE_MODE == 1 )); then PROG_ARGS+=(--test); HAVE_ARGS=1
    else echo "Note: --test only applies to the full suite; ignored for '$EXP_KEY'." >&2; fi
fi
if (( RESUME == 1 )); then
    if (( SUITE_MODE == 1 )); then PROG_ARGS+=(--resume); HAVE_ARGS=1
    else echo "Note: --resume only applies to the full suite; ignored for '$EXP_KEY'." >&2; fi
fi
if (( MODE1_ONLY == 1 )); then
    if (( SUITE_MODE == 1 )); then PROG_ARGS+=(--m1); HAVE_ARGS=1
    else echo "Note: --m1 only applies to the full suite; ignored for '$EXP_KEY'." >&2; fi
fi
if (( FOLLOWUP == 1 )); then
    if (( SUITE_MODE == 1 )); then PROG_ARGS+=(--followup); HAVE_ARGS=1
    else echo "Note: --followup only applies to the full suite; ignored for '$EXP_KEY'." >&2; fi
fi
if (( ABSOLUTE == 1 )); then
    if (( SUITE_MODE == 1 )); then PROG_ARGS+=(--absolute); HAVE_ARGS=1
    else echo "Note: --absolute only applies to the full suite; ignored for '$EXP_KEY'." >&2; fi
fi

if (( NO_MAVEN == 1 )); then
    RUN_CMD=(java "-Xmx${HEAP}" "-Xss${STACK}" -cp "$CLASSES_DIR" "$MAIN_CLASS")
    if (( HAVE_ARGS == 1 )); then RUN_CMD+=("${PROG_ARGS[@]}"); fi
else
    MVN_ARGS=(-q exec:exec
              "-Dexec.mainClass=$MAIN_CLASS"
              "-DheapSize=$HEAP"
              "-DstackSize=$STACK")
    if (( HAVE_ARGS == 1 )); then MVN_ARGS+=("-Dprog.args=${PROG_ARGS[*]}"); fi
    RUN_CMD=(mvn "${MVN_ARGS[@]}")
fi
echo "${RUNNER_PREFIX[*]} ${RUN_CMD[*]}"
echo "Streaming output to console AND $LOG_FILE ..."
echo ""

START_TS=$(date +%s)
if (( DRY_RUN == 0 )); then
    # Tee combines stdout+stderr to console AND log file. PIPESTATUS preserves the runner's exit code.
    set +e
    # ${arr[@]+...} pattern: safe with empty array under `set -u` on macOS bash 3.2.
    ${RUNNER_PREFIX[@]+"${RUNNER_PREFIX[@]}"} "${RUN_CMD[@]}" 2>&1 | tee "$LOG_FILE"
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
