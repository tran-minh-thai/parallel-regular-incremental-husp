# RegIncHUSPM_Parallel — Code overview

Framework for **Parallel Regular Incremental High-Utility Sequential Pattern mining**.
The code follows `paper/VN/proposedVN.tex` and `paper/VN/preliminaryVN.tex`.

## File-to-paper mapping

| File | Role | Paper reference |
|------|------|------------------|
| `QSeqDatabase.java` | Quantitative sequence database stored as **CSR** (flat arrays `items/utils/rems` + offsets `seqEventStart/eventItemStart`), batches appended at the tail | Definition 1; Lemma 3 (`lem:tail`) |
| `DEUCS.java` | Directed estimated co-occurrence structure (I + S), accumulated incrementally | Definition 6; **Algorithm 2** `IncUpdateDEUCS`; Lemma 1 (`lem:additive`) |
| `VerticalUtilityList.java` | Vertical utility list (VUL): `seqId, eventIdx, itemIdx, matchUtility, remainingUtility` + `totalUtility/peuUpperBound/lastSeqId/maxInnerPeriod` | Data structures section |
| `PatternTree.java` | Prefix enumeration tree + `getOrCreateNode` (emerging-pattern fix) | Data structures section; Design notes (2) |
| `AdaptiveBuffer.java` | Adaptive buffer threshold `θ=μ·minUtil`, `f(x)=x^0.25` | **Algorithm 3** `ComputeAdaptiveMu`; Approximation basis |
| **`AlgoPRIncHUSP.java`** | **PROPOSED algorithm — P-RIncHUSP** — orchestrates 4 phases + 6 procedures, ForkJoinPool + RDLB. `numThreads=1` -> the **"Proposed-sequential"** baseline | **Algorithms 1, 4, 5, 6** |
| `AlgoRHUSP.java` | **RHusp** — static RHUSP mining core (PEU), `parallel` flag toggles sequential/parallel | "RHusp" / "Proposed-static" rows |
| `AlgoRHUSPRecompute.java` | **RHusp-Recompute** — oracle, re-mines from scratch on every batch | "RHusp" row |
| `AlgoRIncHUSP.java` | **RIncHusp** [Ishita2022] — SEQUENTIAL incremental competitor (approximate), updates only existing nodes; exhibits 2 defects | "RIncHusp" row |
| `IncrementalHUSPMiner.java` | Common interface | — |
| `RunIncremental.java` | Experiment runner: batch slicing, measures time/memory/coverage | Experiments section |

### Class-name to baseline-label mapping (experimentVN.tex, "Comparison algorithms" table)

| Label in the paper | R/I/P | Implementing class |
|---|---|---|
| **P-RIncHUSP** (proposed) | yes/yes/yes | `AlgoPRIncHUSP` |
| **Proposed-sequential** (reference point $T_1$) | yes/yes/no | `AlgoPRIncHUSP` (`numThreads=1`) |
| **Proposed-static** (ancestor) | yes/no/yes | `AlgoRHUSP(parallel=true)` / `AlgoRHUSPRecompute` |
| **RHusp** [Ishita2022] (oracle) | yes/no/no | `AlgoRHUSP(parallel=false)`, `AlgoRHUSPRecompute` |
| **RIncHusp** [Ishita2022] (competitor) | yes/yes/no | `AlgoRIncHUSP` |
| **IncUSP-Miner+** [IncUSP2018] (incremental HUSP) | no/yes/no | `IncUSPMinerPlusAdapter` -> `IncUSPMinerPlusAlgorithm` |
| **USpan** [USpan2012] / **HUSP-ULL** [HUSPULL2021] (static HUSP, reference) | no/no/no | `ReferenceMiners` -> `USpanAlgorithm` / `HUSPULLAlgorithm` |

The reference baselines use the `common.{Sequence,QItemset,QItem,Pattern}` model; the bridge is
`SeqConverter` (`List<List<int[]>>` <-> `Sequence`, plus a normalized pattern key for cross-matching).
The HUSP group (IncUSP-Miner+, USpan, HUSP-ULL) solves a DIFFERENT problem (non-regular), so it is
reported SEPARATELY (pattern count / runtime) and is NOT mixed into the coverage-vs-oracle measurement.

## Experiments — separate test and official runs, run automatically over a LIST of datasets

**All experiment constants live in `ExpConfig`** (a single source for CONSISTENT, reproducible evaluation):
buffer FLOOR/CEILING `muMin`=0.40 / `muMax`=0.90 (proposed COMBINED adaptive buffer, floor = baseline Fix(0.4) μ);
`muFixHigh`=0.90 (high-μ Fix baseline); `warmupRuns`, `measuredRuns`, `runTimeoutMs` (60 minutes);
`coverageMaxN` (measure recall iff N <= threshold); 4 batch-distribution scenarios `SCEN_A/B/C/D` + S1/S2/S4 flags.
Miners are created via the factory `ExpConfig.newProposed(threads)` (proposed, adaptive) / `newRIncHusp(mu)`
(RIncHusp Fix(μ) baseline). μ-strategy table: SC1 Combined, SC2 Growth, SC3 MinBase, SC4 UtilRatio,
SC5 Fix(0.4), SC6 Fix(0.9). USpan/HUSP-ULL/IncUSP-Miner+ are NOT compared (non-regular, different problem).

Dataset configuration is centralized in **`DatasetCatalog`** (`testSuite()` small/fast with oracle;
`officialSuite()` benchmark). Each class runs in TWO ways:
- **No arguments** -> runs the WHOLE SUITE (Run inside IntelliJ).
- **With arguments** `<seqFile> <eutilFile> [delta] [rho] [...]` -> runs a single dataset (terminal).

Dataset paths are auto-resolved (`DatasetSpec.resolve`), so runs work whether the working
directory is the project root or the module root.

### `ExperimentTest` — TESTING (writes patterns to .txt)
```bash
java -cp out test.ExperimentTest          # whole testSuite (example + example2)
java -cp out test.ExperimentTest datasets/example_seq.txt datasets/example_eui.txt 0.10 0.6
```
Runs each algorithm once, **writes the pattern set to txt** (`test_output/<algo>_<dataset>_..._patterns.txt`,
lines `pattern #UTIL: u #PER: p`), compares against the RHusp oracle, and prints coverage.
Static HUSP baselines are skipped when N > 200.

### `ExperimentOfficial` — OFFICIAL (writes CSV only)
```bash
java -cp out test.ExperimentOfficial      # whole officialSuite -> one combined CSV
java -cp out test.ExperimentOfficial datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.03 0.6 sign.csv
```
Each benchmark point: **1 WARM-UP + 3 MEASURED runs + 60-minute TIMEOUT per run**. Writes ONLY metrics
to CSV (`dataset,scenario,algorithm,delta,rho,threads,n_batches,iteration,runtime_ms,peak_mb,hs_count,coverage,status`)
and **does not store patterns** (to avoid I/O). Scenarios: `compare`, `speedup` (T=1..cores), `delta-sweep`.
A run that exceeds the limit is recorded as `TIMEOUT`.

*Self-adapting:* coverage is skipped when `N>2000` (the in-memory oracle is slow); `delta-sweep` is
skipped when `N>1000`. **Real data** (SIGN, FIFA, ...) has long sequences that make the prototype miner
slow, so those entries are left COMMENTED in `officialSuite()`; uncomment them for a full benchmark
(slow, protected by the timeout).

Helper classes: `DatasetSpec`, `DatasetCatalog`, `ExpUtil` (shared utilities), `SeqConverter`,
`ReferenceMiners`, `IncUSPMinerPlusAdapter`.

## Current mechanism of `AlgoPRIncHUSP.java` — MAINTAIN SHS + PROMOTE

The current version follows the incremental framework of RIncHusp (maintains a persistent HS+SHS tree,
each node holding a VUL) and does NOT re-mine the whole DB per batch:

1. `initialBuild` -> `staticBuild`/`growSubtree` — mine the static `D_old` once, build the tree,
   and STORE a VUL at each node with `sumPeu >= θ`.
2. `processBatch` — orchestrates a batch: `deucs.incUpdate` (Algorithm 2, accumulation — Lemma 1) ->
   `recomputeThresholds`/`buffer.computeMu` (Algorithm 3, adaptive θ) -> `maintain`.
3. `maintain` -> `extendNodeWithNewSeqs` — propagate the NEW sequences top-down, EXTEND the VUL of
   existing nodes from the parent node's VUL (scanning only ΔD), maintaining regularity at the tail (Lemma 3).
4. `matchExtend` — match according to the MAX-measure (i-ext in the same event, s-ext in a later event).
5. `classify` — reclassify: `sumIutil >= minUtil` -> HS (PROMOTED from SHS); `>= θ` -> SHS.

> **Coverage < 100%, depends on θ** (high-coverage approximation). This mechanism does NOT create nodes
> for entirely new patterns — the "GetOrCreateNode emerging pattern" claim requires *frontier growth /
> pre-large* (an extension, not yet implemented). See `datasets/RUNNING_EXAMPLE.md`.

## Package conventions and parameters

- **Package `algorithms`** — algorithms + data structures + baselines + adapters.
- **Package `common`** — `Sequence/Pattern` model for the reference baselines.
- **Package `test`** — all experiment code: `ExperimentTest`, `ExperimentOfficial`,
  `ExpUtil`, `DatasetSpec`, `DatasetCatalog`, `RunIncremental`.

**VARIABLE DICTIONARY — use DESCRIPTIVE names, NOT symbols** (the Greek symbols δ, ρ, θ, μ are only
used in the paper; the code uses full names):

*Thresholds (passed as ratios):*
| Code | Paper | Meaning |
|---|---|---|
| `minUtilRatio` | δ | `minUtil = minUtilRatio × totalDbUtility` |
| `maxRegRatio` | ρ | `maxReg = maxRegRatio × numSequences` |
| `bufferThreshold` | θ | buffer threshold `= bufferFactor × minUtil` |
| `bufferFactor` | μ | buffer factor (P-RIncHUSP: adaptive; RIncHusp: fixed 0.4) |

*Data structures (renamed from abbreviations to full names):*
| Code (new) | Previous | Meaning |
|---|---|---|
| `totalDbUtility` | `UD` | total utility of the whole database |
| `numSequences` | `N` | number of sequences in the database |
| `seqId` | `sid` | sequence identifier |
| `matchUtility` | `acu` | MAX match utility of the pattern in one sequence = u(α,S) |
| `remainingUtility` | `ru` | remaining utility after the match position |
| `totalUtility` | `sumIutil` | Σ matchUtility = u(α) |
| `peuUpperBound` | `sumPeu` | Σ(matchUtility+remainingUtility) = PEU upper bound |
| `lastSeqId` | `lastSid` | the most recent sequence in which the pattern occurs |
| `maxInnerPeriod` | `curMaxPer` | largest inner period (the final period is tracked separately as `trueMaxPer`) |
| `coOccurInEvent`/`coOccurAfter` | `ds_I`/`ds_S` | DEUCS in-event / ordered co-occurrence |

> Keep the DOMAIN NAMES from the paper: class `VerticalUtilityList` (VUL), `DEUCS`, map `SWU`
> (Sequence-Weighted Utilization) — these are standard terms, with explanatory comments.

> **minUtil/maxReg are NOT fixed up front** — they are RECOMPUTED per batch (`recomputeThresholds`)
> because `totalDbUtility` and `numSequences` grow with the data. Passing them as RATIOS is correct:
> as the database grows, the thresholds scale automatically.

## Quick run (single dataset)

```bash
javac -d out src/common/*.java src/algorithms/*.java src/test/*.java
java -cp out test.RunIncremental \
     datasets/example_seq.txt datasets/example_eui.txt 0.10 0.6 "0.667,0.333"
```
Arguments: `<seqFile> <eutilFile> <minUtilRatio> <maxRegRatio> [batchRatios]`.

## Notes on the "draft" parts (to be completed)

- `computeLAPEU` currently uses the PEU bound `(acu+ru)`; the full version tightens it to **LA-PEU**,
  adding only the remaining utility of the locally promising set (see `computeBoundsLA` in
  `AlgoRHUSPMinerParallel`).
- The parallel core (per-thread `MiningContext`, immutable CSR, work-stealing deque) should be ported
  directly from `AlgoRHUSPMinerParallel` for performance; here a `ForkJoinPool` + `RecursiveAction`
  is used at the architectural level.

### Performance — two hot spots FIXED (previously hung on SIGN)
- `pathString` previously walked from the ROOT for EACH node -> O(nodes²) -> **hang** on large trees.
  Added a `parent` pointer (PatternTree.Node) and walk UP to the root -> O(depth).
- `localCandidates` previously scanned the ENTIRE DEUCS map per node -> O(|DEUCS|)/node. Added the
  adjacency index `DEUCS.adjInEvent/adjAfter` (built after each `incUpdate`) -> O(degree).
- SIGN result (730 sequences, δ=0.02): init 167s -> **14s (8 threads) / 49s (1 thread)**, HS unchanged.
- Remaining: `buildChildVUL` scans the projected database (initial static projection cost); going
  faster requires porting the CSR core. The incremental phase (`maintain`) is already fast (~2–5s/batch on SIGN).
