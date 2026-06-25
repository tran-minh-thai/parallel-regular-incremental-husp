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
- No external libraries — pure standard-library Java

Build and test with any Java 11+ JDK:

```bash
javac -d out $(find src -name "*.java")
```

---

## Quick Start

### Single-dataset run (terminal)

```bash
# Compile
javac -d out $(find src -name "*.java")

# Run incremental experiment on the included example dataset
java -cp out test.RunIncremental \
     datasets/example_seq.txt datasets/example_eui.txt \
     0.10 0.6 "0.5,0.5"
```

Arguments: `<seqFile> <eutilFile> <minUtilRatio> <maxRegRatio> [batchRatios]`

- `minUtilRatio` (δ): minUtil = δ × totalDbUtility
- `maxRegRatio` (ρ): maxReg = ρ × numSequences
- `batchRatios`: comma-separated split fractions summing to 1 (default `0.4,0.2,0.2,0.2`)

### Correctness probe

```bash
# Compare P-RIncHUSP recall against the RHusp oracle on SIGN
java -cp out test.RecallProbe \
     datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.03 0.30 4
```

### Official experiment suite

```bash
# Run the full parallel benchmark (outputs a CSV in results/)
java -Xmx8g -cp out test.ExperimentOfficial
```

---

## Datasets

Place dataset files in the `datasets/` directory. The `_seq.txt` file holds the quantitative sequences; the `_eui.txt` file holds the external item profits.

| Dataset | Sequences | Events/seq | Items | Recommended δ | Note |
|---|---|---|---|---|---|
| SIGN | 730 | high | — | 0.030 | Dense; δ≤0.02 OOM at 6 GB |
| LEVIATHAN | 5,834 | low | — | 0.005 | Sparse |
| BIBLE | 36,369 | low | — | 0.0025 | Sparse; matches paper δ |
| FIFA | 20,450 | ≤100 | — | 0.050 | Dense; ~7 min/run — S1 only |
| KOSARAK | 990,002 | low | — | 0.050 | Very large; needs `-Xmx16g` — S1 only |

Included in the repository: `example_seq.txt` / `example_eui.txt` (9 sequences, for unit tests).

Raw SPMF-format files can be converted to the QSDB format used here with:
```bash
java -cp out SPMF_Converter
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
src/
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

`ExperimentOfficial` writes one CSV row per (dataset, scenario, algorithm, thread count, iteration):

```
dataset, scenario, algorithm, delta, rho, threads, n_batches, iteration, runtime_ms, peak_mb, hs_count, coverage, status
```

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
