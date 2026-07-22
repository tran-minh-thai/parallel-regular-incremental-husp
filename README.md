# P-RIncHUSP

**Parallel incremental mining of regular high-utility sequential patterns**

A Java implementation of P-RIncHUSP: it mines high-utility sequential patterns that also occur
*regularly* — no gap between occurrences larger than a bound — over a database that keeps growing,
without re-mining the history on every update.

## What it does

The result is **exact**: the pattern set equals what a full re-mine of the updated database would
return. Incremental miners in this family usually give up some completeness for speed; this one does
not have to.

| | |
|---|---|
| **Exact incremental result** | Two bounds close two independent gaps: regularity is pruned at `ρ·N_final` while seeding, and a discovery phase mines the increment at `minUtil − θ₀`. A partition lemma fixes the buffer factor at `μ = 1` instead of leaving it to be tuned. |
| **Parallel and deterministic** | Independent prefix subtrees run on a thread pool; the result never depends on the thread count. |
| **Content-driven maintenance** | A shared prefix is matched once on behalf of every pattern extending it, over disjoint sequence ranges. |
| **Exact max-measure utility** | A multi-position vertical utility list, so incremental utilities agree exactly with a re-mine. |

Being exact does not cost speed. Maintenance is nearly flat in the number of update batches while
re-mining is linear in it, so past roughly three to eight batches the incremental method is also the
faster one — at thirty-two batches, 14.8x (SIGN), 4.6x (LEVIATHAN) and 16.9x (BIBLE) against the same
parallel engine re-mining every batch.

## Requirements

Java 11 or later. Maven is optional: the project has no runtime dependencies, so `javac` alone is
enough and needs no network.

## Quick start

```bash
# Build
javac --release 11 -d out $(find src/main/java -name '*.java')

# Smoke test: every scenario on the tiny example datasets, about three minutes
java -Xmx2g -cp out test.ExperimentOfficial --test

# A single dataset
java -cp out test.RunIncremental datasets/example_seq.txt datasets/example_eui.txt 0.10 0.6
```

Run the smoke test before anything long. Two lines of its output are the ones that matter: the
exactness ablation (`S10-exactness`) must reach recall `1.0000` with both bounds enabled and must
miss patterns with either bound alone, and `S3 HS invariance` must report `OK`.

## Running the full suite

```bash
java -Xmx16g -cp out test.ExperimentOfficial              # every dataset
java -Xmx16g -cp out test.ExperimentOfficial --only=SIGN  # one dataset
java -Xmx16g -cp out test.ExperimentOfficial --resume     # continue an aborted run
```

Wrapper scripts handle building, logging, and — on macOS — keeping the machine awake:

```bash
./run_experiments.sh              # full suite; --help lists the options
./run_experiments.sh --no-maven   # build with javac rather than Maven, so no network is needed
./results_status.sh               # which run directories are complete
```

`run_experiments.ps1` and `RUN_ALL_WINDOWS.bat` do the same on Windows. `RUNNING.md` covers the
workflow in more detail, including how to retune a threshold.

Two things are worth getting right, because they make the numbers wrong rather than merely slow.
Keep the heap well below physical RAM: if it approaches RAM the JVM swaps instead of failing, which
distorts every timing and can fill the disk. And on a laptop, plug in and disable power saving — the
first log line reports the thread sweep, and if it says the sweep was truncated then the machine is
not offering all of its cores.

## Datasets

`datasets/` holds `<name>_seq.txt` (quantitative sequences) and `<name>_eui.txt` (item profits). Only
the small example ships with the repository; the benchmark datasets come from SPMF and are converted
with `SPMF_Converter`.

| Dataset | Sequences | δ | Notes |
|---|---|---|---|
| SIGN | 730 | 0.030 | Dense. 0.03 is a floor: below it the discovery phase explodes under the B-Increasing split |
| LEVIATHAN | 5,834 | 0.020 | |
| BIBLE | 36,369 | 0.010 | |
| FIFA | 20,450 | 0.050 | Dense, long sequences; scalability scenario only |
| KOSARAK | 990,002 | 0.015 | Sparse; needs `-Xmx16g`. Scalability only, and recall is skipped — an oracle is impractical at this size |

δ does not transfer between datasets: too high leaves too few patterns to compare against, too low
exhausts memory. `DatasetCatalog.java` records the measured range for each one, and `DeltaProbe`
sweeps a new dataset.

## Scenarios

Configured in `ExpConfig`, implemented in `ExperimentOfficial`.

| | Question it answers |
|---|---|
| S1 | How does runtime scale with threads? (T ∈ {1, 2, 4, 8, 10}) |
| S2 | How does it compare against the baselines, and against re-mining? |
| S3 | Do results depend on the thread count? They must not; checked from the S1 runs |
| S4 | Is it stable across batch distributions — uniform, increasing, oscillating, decreasing? |
| S5 | What does the maintenance strategy cost, against a per-pattern inverted index? |
| S6 | How sensitive is it to the utility threshold δ? |
| S7 | How sensitive is it to the regularity threshold ρ, and where does the approximate variant fail? |
| S8 | Past how many batches does incremental maintenance beat re-mining? |
| S9 | Does the seed-split factor affect correctness, and where is it cheapest? |
| S10 | Which seed bound closes which gap? |
| S11 | Does the baseline comparison hold as the number of update batches grows? (k ∈ {4, 16, 64}) |

S5 to S11 run only on datasets small enough to have an oracle.

Every configuration is warmed up once, then measured over five independent runs. Runtime is
reported as mean ± standard deviation, peak memory and recall as medians.

### One naming difference between the code and the paper

The code calls the proposed miner's seed-split factor `mu` throughout — the CSV column, the
`S9-musweep` scenario tag and `ExpConfig.S9_MUS`. The paper calls it **λ**, and reserves μ for the
baselines' semi-high-utility buffer factor, which is a different quantity. The names were left
alone here because the CSV column feeds the table generator, so renaming would break every stored
result. When reading a result file next to the paper: `mu` in an `S9-musweep` row is the paper's λ,
while `RIncHusp-Fix0.4` and `RIncHusp-Fix0.9` carry the paper's μ in their names.

## Output

Each suite run creates `results/run_<timestamp>_<confighash>/`:

| File | Contents |
|---|---|
| `results.csv` | One row per measured run; completed cells only |
| `meta.properties` | Environment and the configuration that produced the numbers, plus `config.signature` and `status` |
| `DONE` | Written only if the whole suite finished; its absence means the run was aborted |
| `completed.txt`, `datasets_done.txt` | Resume bookkeeping |

CSV columns:

```
dataset,scenario,distribution,algorithm,mu,minUtilRatio,maxRegRatio,threads,n_batches,
iteration,runtime_ms,build_ms,incr_ms,peak_mb,hs_count,shs_count,recall,status
```

`./results_status.sh` lists each run directory as complete or aborted. Runs whose
`config.signature` differs came from different configurations — never mix their numbers.

## Algorithms and baselines

| Class | In the paper | R / I / P | Role |
|---|---|---|---|
| `AlgoPRIncHUSP` | P-RIncHUSP | ✓ / ✓ / ✓ | Proposed |
| `AlgoPRIncHUSP` (T=1) | P-RIncHUSP-seq | ✓ / ✓ / — | Same engine on one thread; the speedup reference |
| `AlgoRIncHUSP` | RIncHusp Fix(μ) | ✓ / ✓ / — | The sequential incremental predecessor |
| `AlgoRHUSP`, `AlgoRHUSPRecompute` | RHusp | ✓ / — / — | Static mining; the ground truth for recall |
| `AlgoRHUSPMinerParallel` | ParRemine-RDLB | ✓ / — / ✓ | Parallel static engine: seeds `D_old`, and re-mines as a baseline |
| `AlgoRemine`, `AlgoParRemine` | Remine-static | ✓ / — / ✓ | Re-mine on every batch |
| `USpanAlgorithm`, `HUSPULLAlgorithm`, `IncUSPMinerPlusAlgorithm` | — | — | High-utility mining without the regularity constraint |

R = regularity, I = incremental, P = parallel. The last group answers a different question, so it is
reported separately and never enters the recall comparison.

## Parameters

| Code | Paper | Meaning |
|---|---|---|
| `minUtilRatio` | δ | `minUtil = δ × totalDbUtility` |
| `maxRegRatio` | ρ | `maxReg = ρ × numSequences` |
| `bufferFactor` | μ | Seed threshold `= μ × minUtil`; 1 for the proposed miner, 0.4 and 0.9 for the baselines |
| `numThreads` | T | Worker threads |

Thresholds are ratios rather than absolute values, so they scale as the database grows; they are
recomputed on every batch.

## Layout

```
src/main/java/
  algorithms/   miners, data structures, baselines   (see algorithms/README.md)
  common/       sequence model used by the reference baselines
  test/         experiment harness: runners, configuration, probes
datasets/       example data; RUNNING_EXAMPLE.md works through it by hand
```

`algorithms/README.md` maps the classes onto the manuscript and explains the exactness argument in
more detail.

## References

1. Ishita, Ahmed, Leung. *New approaches for mining regular high utility sequential patterns.*
   Applied Intelligence, 2022. (RHusp / RIncHusp)
2. Yin, Zheng, et al. *USpan: An Efficient Algorithm for Mining High Utility Sequential Patterns.*
   KDD, 2012.
3. Alkan, Demiriz. *Efficient mining of high utility sequential patterns.* IEEE Trans. Cybernetics,
   2021. (HUSP-ULL)
4. Guo, et al. *Incremental High Utility Sequential Pattern Mining.* ACM TIST, 2018. (IncUSP-Miner+)
