# Code overview

Parallel, incremental mining of regular high-utility sequential patterns (P-RIncHUSP). This note maps
the code onto the manuscript and explains the conventions, so the two can be read side by side.

The method is **exact**: the pattern set it returns equals a full re-mine of the updated database. That
rests on two bounds that close independent gaps, plus a threshold choice that is forced rather than
tuned — see [Exactness](#exactness) below.

## Building and running

The layout is standard Maven, but nothing here needs Maven; plain `javac` is enough.

```bash
javac --release 11 -d out $(find src/main/java -name '*.java')

java -cp out test.ExperimentOfficial --test        # small datasets, finishes in seconds
java -cp out test.ExperimentOfficial              # the full benchmark suite
```

Dataset paths resolve relative to either the project root or the module root, so runs work from
either directory. Thresholds and datasets are configured in `test.ExpConfig` and
`test.DatasetCatalog`; the runner needs no edits. See `RUNNING.md` at the repository root for the
full experiment workflow.

## Core classes

| File | Role | In the paper |
|---|---|---|
| `QSeqDatabase.java` | Quantitative sequence database in CSR form (flat `items/utils/rems` plus offsets); batches are appended at the tail | Definition 1; Lemma 3 (tail-only update) |
| `DEUCS.java` | Directed estimated-utility co-occurrence structure, accumulated per batch | Definition 6; Algorithm `IncUpdateDEUCS`; Lemma 1 (additivity) |
| `VerticalUtilityList.java` | Vertical utility list: one entry per non-dominated end position, plus the regularity fields `lastSeqId` and `maxInnerPeriod` | Data structures |
| `PatternTree.java` | Prefix enumeration tree | Data structures |
| `AdaptiveBuffer.java` | Seed threshold θ₀ = λ·δ·U(D_old) (the class name predates λ; at λ=1 nothing is buffered). Fixed at λ = 1 for the proposed miner; the adaptive strategies are exercised only by the diagnostic probes | Seed-split factor discussion |
| **`AlgoPRIncHUSP.java`** | **The proposed algorithm.** Seeding, parallel content-driven maintenance, discovery. `numThreads=1` gives the sequential reference point | Algorithms `P-RIncHUSP`, `ParallelEnumerate`, `Extend`, `ProcessBatch`; Theorem 1; Lemma 4 |
| `AlgoRHUSPMinerParallel.java` | Parallel static engine from the companion study (CSR, EUCS + LA-PEU, RDLB). Used to seed `D_old`, and as the re-mining baseline | Comparison algorithms |
| `AlgoRHUSP.java`, `AlgoRHUSPRecompute.java` | Static RHUSP mining; re-mining from scratch supplies the ground truth for recall | Oracle |
| `AlgoRIncHUSP.java` | The sequential incremental predecessor, run at a fixed buffer factor | `RIncHusp Fix(μ)` rows |
| `AlgoRemine.java`, `AlgoParRemine.java` | Re-mine the whole database on every batch, sequential and parallel | `Remine-static`, `ParRemine-RDLB` rows |
| `IncrementalHUSPMiner.java` | Interface shared by every miner the runner drives | — |

`USpanAlgorithm`, `HUSPULLAlgorithm` and `IncUSPMinerPlusAlgorithm` mine high-utility sequences
*without* the regularity constraint. They answer a different question, so they are reported separately
and never enter the recall comparison. `SeqConverter` bridges them to the `common` model.

## Exactness

Incremental maintenance is cheap but can only promote patterns that were seeded, so a seed-once design
has two ways to lose a pattern, and each needs its own bound. `AlgoPRIncHUSP` carries a flag for each,
and scenario S10 switches them on and off:

- **`seedPruneByFinalN`** — prune regularity at ρ·N_final rather than ρ·N_current. A gap inside `D_old`
  survives unchanged when data is appended while the threshold grows with N, so a pattern can be
  irregular now and regular once the database is complete.
- **`discoverExact`** — mine the increment at `minUtil − θ₀`. Utility is additive over the partition,
  so this recovers exactly the patterns that were too weak in `D_old` to be seeded.

Both are needed in general: on the benchmark datasets the discovery bound does the work, while on
constructed cases where a pattern is irregular in `D_old` but regular later, only the regularity bound
helps.

λ = 1 is not a tuned constant. It makes the seed threshold δ·U(D_old) and the discovery threshold
δ·U(ΔD), so each part of the database is mined at its own natural threshold — the condition the
partition lemma needs for the union of the two to be complete. Scenario S9 sweeps λ and shows recall
unchanged at 1.0 throughout — correctness does not depend on it — while cost traces a U: seeding gets
cheaper as θ₀ rises and discovery gets dearer, so the two trade off.

λ = 1 lands at or beside the bottom of that U, but do not claim it *is* the minimum. Measured: it is
exactly the cheapest on both SIGN and BIBLE, and about 24% above a shallower minimum at λ = 0.7 on
LEVIATHAN. The point is not that 1 is optimal — it is that 1 is *fixed by the lemma* rather than
searched for, and costs at most about a quarter against searching, on the datasets measured.

## How the miner works

1. `initialBuild` mines `D_old` once and stores a vertical utility list per surviving pattern. With
   `seedWithEngine05` the enumeration is delegated to the companion parallel engine, which is
   substantially faster and returns the state needed to continue tracking regularity.
2. `processBatch` appends a batch, accumulates `DEUCS` (Lemma 1), recomputes the thresholds — both are
   ratios, so they scale as the database grows — and calls `maintain`.
3. `maintain` propagates the new sequences through the pattern set. Maintenance is content-driven: a
   shared prefix is matched once for every pattern extending it, and the work is split over disjoint
   ascending sequence ranges, which keeps the result independent of the thread count. Regularity is
   updated at the tail in constant time (Lemma 3).
4. `classify` promotes patterns that have reached `minUtil`.
5. Querying the result runs `discoverFrom` once over the accumulated increment, which is what makes
   the answer exact rather than high-coverage.

## Naming

The manuscript uses Greek symbols; the code spells the same quantities out. Both appear in comments
where it helps to line them up.

| Code | Paper | Meaning |
|---|---|---|
| `minUtilRatio` | δ | `minUtil = minUtilRatio × totalDbUtility` |
| `maxRegRatio` | ρ | `maxReg = maxRegRatio × numSequences` |
| `bufferThreshold` | θ₀ | seed threshold `= bufferFactor × minUtil` |
| `bufferFactor` | λ | seed-split factor: θ₀ = λ·δ·U(D_old), the rest of minUtil goes to discovery. 1 for the proposed miner. NOT the baselines' μ, which is a *reduction* factor on minUtil (0<μ<1) that retains semi-high-utility patterns — at λ=1 this miner retains none, and discovery covers that role instead |
| `totalDbUtility` | U(D) | total utility of the database |
| `numSequences` | N | number of sequences |
| `matchUtility` | u(α,S) | max-measure utility of the pattern in one sequence |
| `remainingUtility` | ru | utility remaining after the match position |
| `totalUtility` | u(α) | Σ `matchUtility` |
| `peuUpperBound` | PEU | Σ (`matchUtility` + `remainingUtility`) |
| `lastSeqId` | — | most recent sequence containing the pattern |
| `maxInnerPeriod` | — | largest inner period; the final period is tracked separately |

Domain names stay as in the literature: `VerticalUtilityList` (VUL), `DEUCS`, `SWU`.

Thresholds are recomputed per batch rather than fixed up front, because `totalDbUtility` and
`numSequences` both grow with the data. Passing ratios rather than absolute values is what makes that
work.

## Packages

- `algorithms` — miners, data structures, baselines, adapters.
- `common` — the `Sequence`/`Pattern` model used by the reference baselines.
- `test` — everything experimental: the runners (`ExperimentOfficial`, `ExperimentTest`), the
  configuration (`ExpConfig`, `DatasetCatalog`, `DatasetSpec`), shared helpers (`ExpUtil`,
  `RunContext`), and the probes and benches used to size parameters (`DeltaProbe`,
  `KosarakDeltaProbe`, `SeedBench`, `MaintainBench`, and others).
