# P-RIncHUSP

**Parallel Regular Incremental High-Utility Sequential Pattern Mining**

A Java implementation of P-RIncHUSP — a parallel, incremental framework for mining Regular High-Utility Sequential Patterns (RHUSP) from a growing quantitative sequence database.

---

## Overview

Mining **high-utility sequential patterns** that are also **regular** (bounded maximum gap between occurrences) is a core data-mining task. P-RIncHUSP extends the incremental baseline [RIncHusp, Ishita2022] with three contributions:

| Contribution | Description |
|---|---|
| **Parallel enumeration** | Root-level partitioning over a `ForkJoinPool`; T worker threads process independent subtrees with no shared mutable state |
| **Adaptive buffer threshold** | Per-batch μ ∈ [μ_min, μ_max] computed from three risk signals (utility ratio, growth rate, threshold difficulty), retaining more candidates when risk is high |
| **Correct max-measure utility** | Multi-position Vertical Utility List + DP frontier; incremental update yields the exact same utility as a full re-mine |

The framework also includes re-implementations of several competing baselines and static HUSP miners for comparison.

---

## Requirements

- **Java 11** or later (uses `ForkJoinPool.commonPool`, `ConcurrentHashMap`)
- **Maven 3.6+** (build tool)
- No external runtime libraries — pure standard-library Java

Build:

```bash
mvn compile
```

Compiled classes are placed under `target/classes/`.

---

## Quick Start

### Single-dataset run

```bash
mvn compile
mvn exec:java -Dexec.mainClass=test.RunIncremental \
              -Dexec.args="datasets/example_seq.txt datasets/example_eui.txt 0.10 0.6 0.5,0.5"
```

Arguments: `<seqFile> <eutilFile> <minUtilRatio> <maxRegRatio> [batchRatios]`

- `minUtilRatio` (δ): minUtil = δ × totalDbUtility
- `maxRegRatio` (ρ): maxReg = ρ × numSequences
- `batchRatios`: comma-separated split fractions summing to 1 (default `0.4,0.2,0.2,0.2`)

### Correctness probe

```bash
mvn exec:java -Dexec.mainClass=test.RecallProbe \
              -Dexec.args="datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.03 0.30 4"
```

### Official experiment suite

```bash
# Forked JVM with -Xmx16g (configured in the `benchmark` profile).
# Lower the heap with -DheapSize=8g if the host has less RAM.
mvn -Pbenchmark exec:exec
```

### Convenience wrappers (compile + run + tee-log)

Both wrappers default to the full benchmark suite. Pass `-Experiment <name>`
(Windows) or `-e <name>` (macOS/Linux) to pick a specific experiment instead.
Log file: `results/run_log_<experiment>_<timestamp>.txt`.

**Windows (PowerShell):**
```powershell
.\run_experiments.ps1                                # full suite, -Xmx16g -Xss4m
.\run_experiments.ps1 -Experiment recall             # RecallProbe only
.\run_experiments.ps1 -Experiment mu -HeapGb 8       # MuProbe with 8 GB heap
.\run_experiments.ps1 -SkipBuild -DryRun             # show command without running
```

**macOS / Linux (bash):**
```bash
./run_experiments.sh                                 # full suite, -Xmx16g -Xss4m
./run_experiments.sh -e recall                       # RecallProbe only
./run_experiments.sh -e mu --heap 8g                 # MuProbe with 8 GB heap
./run_experiments.sh --resume                        # continue an aborted suite run
./run_experiments.sh --no-maven                      # build/run with javac+java (no Maven, no network)
./run_experiments.sh --skip-build --dry-run          # show command without running
./run_experiments.sh --no-caffeinate                 # macOS: don't keep Mac awake
./results_status.sh                                  # which run dirs are VALID vs PARTIAL/stale
```

> `--no-maven` exists because the project has **no external dependencies**: only Maven's own build
> plugins need the network. Use it when `repo.maven.apache.org` is unreachable (offline machine).

> On **macOS**, the script auto-wraps `mvn` with `caffeinate -i` so the system
> won't idle-sleep during multi-hour benchmark runs. Pass `--no-caffeinate`
> to opt out. The banner prints `Sleep guard: on/off` so you can confirm.

**Experiment choices** (same in both wrappers):

| Name | Maven main class | What it does |
|---|---|---|
| `all` / `official` (default) | `test.ExperimentOfficial` | Full S1-S4 suite over `DatasetCatalog.officialSuite()`, writes CSV |
| `test` | `test.ExperimentTest` | Runs each algorithm once and writes pattern `.txt` files |
| `perf` | `test.PerfProbe` | Single-run performance probe |
| `recall` | `test.RecallProbe` | Quick recall measurement vs. RHusp oracle |
| `mu` | `test.MuProbe` | μ buffer-factor sensitivity diagnostic |
| `oracle` | `test.OracleValidation` | Validate oracle vs. reference RHusp miner |

**Tunable JVM resources** (defaults shown):

| Param | PowerShell | bash | Default | Notes |
|---|---|---|---|---|
| Heap | `-HeapGb 16` | `--heap 16g` | 16 GB | KOSARAK needs >=16 GB; others fit in 8 |
| Stack | `-StackMb 4` | `--stack 4m` | 4 MB | JVM default (~1 MB) can StackOverflow on FIFA |

> The Java package `test` here is *runtime* code (the experiment harness), not
> JUnit tests. It is kept on the main classpath under `src/main/java/test/`;
> there is no `src/test/java/` directory.

---

## Datasets

Place dataset files in the `datasets/` directory. The `_seq.txt` file holds the quantitative sequences; the `_eui.txt` file holds the external item profits.

| Dataset | Sequences | Events/seq | Items | Recommended δ | Note |
|---|---|---|---|---|---|
| SIGN | 730 | high | — | 0.030 | Dense; δ≤0.02 OOM at 6 GB. **S1 only** — under S4's `B-Increasing` (10% first batch) the seeding threshold collapses and the SHS set explodes combinatorially → OOM even at 32 GB (T-independent) |
| LEVIATHAN | 5,834 | low | — | 0.005 | Sparse |
| BIBLE | 36,369 | low | — | 0.0025 | Sparse; matches paper δ |
| FIFA | 20,450 | ≤100 | — | 0.050 | Dense; ~7 min/run — S1 only |
| KOSARAK | 990,002 | low | — | 0.050 | Very large; needs `-Xmx16g` — S1 only |

Included in the repository: `example_seq.txt` / `example_eui.txt` (9 sequences, for unit tests).

Raw SPMF-format files can be converted to the QSDB format used here with:
```bash
mvn exec:java -Dexec.mainClass=SPMF_Converter
```

---

## Experiment Scenarios

All scenarios are implemented in `test/ExperimentOfficial.java` and configured in `test/ExpConfig.java`.

| Scenario | Goal | Measured metrics |
|---|---|---|
| **S1 — Scalability** | Speedup and efficiency as T increases (T ∈ {1, 2, 4, 8, 16}) | Runtime (ms), Speedup S(p)=T₁/Tₚ, Efficiency E(p)=S(p)/p, Peak memory (MB) |
| **S2 — Comparison** | P-RIncHUSP (best T) vs. sequential baselines | Runtime, peak memory, #HS, #SHS, Recall% |
| **S3 — Correctness** | HS found and Recall% are identical across T values (determinism) | #HS, Recall%, equals T=1 result |
| **S4 — Distribution robustness** | Stability across 4 batch-distribution scenarios A/B/C/D | Speedup and Recall% per scenario |

### Batch distribution scenarios

| ID | Ratios | Pattern |
|---|---|---|
| A | 25 / 25 / 25 / 25 | Uniform |
| B | 10 / 20 / 30 / 40 | Increasing |
| C | 40 / 10 / 40 / 10 | Oscillating |
| D | 40 / 30 / 20 / 10 | Decreasing |

---

## Strategy Variants

The adaptive buffer strategy is selected via `AdaptiveBuffer.Strategy`:

| Code | Strategy | Description |
|---|---|---|
| SC1 | COMBINED | min of all three risk signals (main proposal) |
| SC2 | GROWTH | growth rate r_G only |
| SC3 | MINBASE | threshold difficulty r_θ only |
| SC4 | UTILRATIO | utility ratio r_U only |
| SC5 | FIX(0.4) | fixed μ=0.40 — equivalent to RIncHusp baseline |
| SC6 | FIX(0.9) | fixed μ=0.90 — conservative buffer (few buffered patterns) |

---

## Algorithms and Baselines

| Class | Label in paper | R / I / P | Description |
|---|---|---|---|
| `AlgoPRIncHUSP` | **P-RIncHUSP** | ✓ / ✓ / ✓ | Proposed algorithm (parallel + adaptive buffer) |
| `AlgoPRIncHUSP` (T=1) | Proposed-sequential | ✓ / ✓ / — | Same engine, single thread |
| `AlgoRIncHUSP` | **RIncHusp** [Ishita2022] | ✓ / ✓ / — | Sequential incremental competitor; fixed μ=0.4; exact max-measure |
| `AlgoRHUSP` (parallel=false) | **RHusp** [Ishita2022] | ✓ / — / — | Static oracle; used to measure Recall% |
| `AlgoRHUSPRecompute` | Proposed-static | ✓ / — / ✓ | Re-mines the full DB from scratch on every batch |
| `IncUSPMinerPlusAdapter` | **IncUSP-Miner+** [2018] | — / ✓ / — | Incremental HUSP without regularity (ablation baseline) |
| `USpanAlgorithm` | **USpan** [2012] | — / — / — | Static HUSP reference |
| `HUSPULLAlgorithm` | **HUSP-ULL** [2021] | — / — / — | Static HUSP reference |

R = Regularity constraint, I = Incremental, P = Parallel.

> USpan and HUSP-ULL solve a different problem (non-regular HUSP); they are reported separately (pattern count / runtime) and are **not** mixed into the Recall% comparison against the RHUSP oracle.

---

## Project Structure

```
pom.xml                Maven build descriptor (Java 11, no runtime deps)
run_experiments.ps1    Windows wrapper: mvn compile + benchmark run + tee log
src/main/java/
  algorithms/          Core algorithm + data structures
    AlgoPRIncHUSP.java         Proposed parallel algorithm
    AlgoRIncHUSP.java          RIncHusp baseline (incremental)
    AlgoRHUSP.java             RHusp static miner (oracle)
    AlgoRHUSPMiner.java        Full-featured RHUSP miner (EUCS + LA-PEU)
    AlgoRHUSPRecompute.java    Re-mine-from-scratch reference
    AdaptiveBuffer.java        Adaptive μ computation (Algorithm 3)
    DEUCS.java                 Directed co-occurrence structure (Algorithm 2)
    VerticalUtilityList.java   Multi-position VUL
    QSeqDatabase.java          CSR sequence database
    PatternTree.java            Prefix enumeration tree
    IntHashSet.java            Primitive open-addressing set
    IntLongHashMap.java        Primitive open-addressing map
    IncrementalHUSPMiner.java  Common interface
    IncUSPMinerPlusAlgorithm.java  IncUSP-Miner+ [2018]
    IncUSPMinerPlusAdapter.java
    USpanAlgorithm.java        USpan [2012]
    HUSPULLAlgorithm.java      HUSP-ULL [2021]
    ReferenceMiners.java       Runner for static HUSP baselines
    SeqConverter.java          Model adapter + canonical pattern key
    MemMeter.java              Per-run heap peak measurement

  common/              Sequence model for reference baselines
    Sequence.java
    QItemset.java / QItem.java
    Pattern.java / DatasetLoader.java

  test/                Experiment harness
    ExperimentOfficial.java    Official benchmark runner (CSV output)
    ExperimentTest.java        Test runner (pattern file output + oracle check)
    ExpConfig.java             All experiment constants (single source of truth)
    DatasetCatalog.java        Dataset list + δ / ρ parameters
    DatasetSpec.java           Per-dataset specification
    ExpUtil.java               Shared loading / splitting / oracle utilities
    RunIncremental.java        Simple CLI incremental runner
    RecallProbe.java           Quick recall probe vs. oracle
    PerfProbe.java             Single-run performance probe
    MuProbe.java               μ sensitivity diagnostic
    OracleValidation.java      Validate oracle vs. reference RHusp miner

  SPMF_Converter.java    Convert SPMF-format files to QSDB (_seq + _eui)

datasets/
  example_seq.txt / example_eui.txt    Running example (9 sequences)
  SIGN_seq.txt / SIGN_eui.txt          ...
  LEVIATHAN_seq.txt / LEVIATHAN_eui.txt
  BIBLE_seq.txt / BIBLE_eui.txt
  RUNNING_EXAMPLE.md    Worked example with ground-truth verification
```

---

## Key Parameters

| Code name | Symbol | Meaning |
|---|---|---|
| `minUtilRatio` | δ | minUtil = δ × totalDbUtility |
| `maxRegRatio` | ρ | maxReg = ρ × numSequences |
| `bufferFactor` | μ | Buffer threshold = μ × minUtil |
| `bufferFactorMin` | μ_min | Lower bound of adaptive μ (default 0.40) |
| `bufferFactorMax` | μ_max | Upper bound of adaptive μ (default 0.90) |
| `numThreads` | T | Number of worker threads |

---

## Output Format

Each suite run creates one **self-describing directory** `results/run_<timestamp>_<confighash>/`:

| File | Contents |
|---|---|
| `results.csv` | One row per (dataset, scenario, distribution, algorithm, threads, iteration). Holds **only completed cells** — partial rows left by a crashed cell are pruned on `--resume`. |
| `meta.properties` | Environment (OS, JVM, cores, max heap, host, git commit) **and** the config that produced the numbers (per-dataset δ/ρ/s1Only, μ band, warm-up/measured runs, timeout, S1/S2/S4 switches) + `config.signature` + `status`. |
| `DONE` | Written **only** when the whole suite finished. Present ⇒ results are complete and valid; absent ⇒ the run was aborted. |
| `completed.txt`, `datasets_done.txt` | Resume bookkeeping (which cells / datasets already finished). |

CSV columns:

```
dataset,scenario,distribution,algorithm,mu,minUtilRatio,maxRegRatio,threads,n_batches,iteration,runtime_ms,peak_mb,hs_count,shs_count,recall,status
```

### Telling valid results from stale ones

Run `./results_status.sh` — it lists every run directory with its status and signature:

- **VALID** (has `DONE`) → complete, usable numbers.
- **PARTIAL** (no `DONE`) → aborted run. Continue it with `./run_experiments.sh --resume`, or delete the directory.
- Runs whose `config.signature` differs came from a **different configuration** (changed δ / ρ / μ / suite / scenario switches). **Never mix their numbers** in one analysis.

`ExperimentTest` additionally writes a pattern file per algorithm:

```
<(1 2)(3)> #UTIL: 450 #PER: 2
<(5)> #UTIL: 310 #PER: 3
```

---

## References

1. Ishita, Ahmed, Leung. *New approaches for mining regular high utility sequential patterns.* Applied Intelligence, 2022. (**RHusp / RIncHusp**)
2. Yin, Zheng, et al. *USpan: An Efficient Algorithm for Mining High Utility Sequential Patterns.* KDD, 2012.
3. Alkan, Demiriz. *Efficient mining of high utility sequential patterns.* IEEE Trans. Cybernetics, 2021. (**HUSP-ULL**)
4. Guo, et al. *Incremental High Utility Sequential Pattern Mining.* ACM TIST, 2018. (**IncUSP-Miner+**)
