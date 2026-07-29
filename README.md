# P-RIncHUSP

**Parallel incremental mining of regular high-utility sequential patterns**

A Java implementation of P-RIncHUSP: it mines high-utility sequential patterns that also occur
*regularly* — no gap between occurrences larger than a bound — over a database that keeps growing,
without re-mining the history on every update.

The regularity bound comes in two modes. In the **absolute** mode (`--absolute`, the mode the study
reports) each dataset declares a constant `B`: a pattern is regular when it recurs at least every
`B` sequences, at every point in time — a given number, like the utility threshold, never derived
from any database size. The **relative** mode (`maxReg = ρ·N`, the definition inherited from the
sequential line of work) is kept for comparison; its bound rises as the database grows, which is
where that formulation's memory pathologies come from.

## What it does

The result is **exact**: the pattern set equals what a full re-mine of the updated database would
return. Incremental miners in this family usually give up some completeness for speed; this one does
not have to.

| | |
|---|---|
| **Exact incremental result** | Two independent gaps get two bounds: regularity is pruned at the declared `B` (in relative mode, at `ρ·N_final`) while seeding, and a discovery phase mines the increment at `minUtil − θ₀`. A partition lemma fixes the seed-split factor at 1 instead of leaving it to be tuned. |
| **Parallel and deterministic** | Independent prefix subtrees run on a thread pool; the result never depends on the thread count. |
| **Content-driven maintenance** | A shared prefix is matched once on behalf of every pattern extending it, over disjoint sequence ranges. |
| **Exact max-measure utility** | A multi-position vertical utility list, so incremental utilities agree exactly with a re-mine. |

Being exact does not cost speed. Maintenance is nearly flat in the number of update batches while
re-mining is linear in it, so past a small crossover — below two batches on BIBLE, between two and
eight on the others — the incremental method is also the faster one. At thirty-two batches it leads
the same parallel engine re-mining every batch by 14.3x (SIGN), 7.5x (LEVIATHAN), 37.1x (BIBLE) and
11.5x (C8T1S5I8N5K), and the gap keeps widening with the batch count.

Under the declared bound, peak memory is comparable to per-batch re-mining on the reported
configurations — equal on SIGN and C8T1S5I8N5K at sixty-four batches, within about 1.5x on
LEVIATHAN and BIBLE. The honest boundary is dense data under a loose bound: four cells of the suite
exhaust a 24 GB heap there, for every algorithm alike, and the run records them as errors rather
than hiding them.

## Requirements

Java 11 or later. Maven is optional: the project has no runtime dependencies, so `javac` alone is
enough and needs no network.

## Quick start

```bash
# Build
javac --release 11 -d out $(find src/main/java -name '*.java')

# Smoke test: every scenario on the tiny example datasets that ship with the repository
java -Xmx2g -cp out test.ExperimentOfficial --test

# The same smoke test under the declared absolute bound (the reported mode)
java -Xmx2g -cp out test.ExperimentOfficial --test --absolute

# Fetch the benchmark datasets (about 30 MB) before running anything on real data
bash fetch_datasets.sh

# A single dataset
java -cp out test.RunIncremental datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03
```

Run the smoke test before anything long. Two lines of its output are the ones that matter: the
exactness ablation (`S10-exactness`) must reach recall `1.0000` with both bounds enabled and must
miss patterns with either bound alone, and `S3 HS invariance` must report `OK`.

## Running the full suite

```bash
java -Xmx24g -cp out test.ExperimentOfficial --absolute   # the suite the study reports
java -Xmx16g -cp out test.ExperimentOfficial              # the relative-mode suite
java -Xmx16g -cp out test.ExperimentOfficial --only=SIGN  # one dataset
java -Xmx16g -cp out test.ExperimentOfficial --resume     # continue an aborted run
```

Wrapper scripts handle building, logging, and — on macOS — keeping the machine awake:

```bash
./run_experiments.sh --absolute   # the reported suite; --help lists every option
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

`datasets/` holds `<name>_seq.txt` (quantitative sequences) and `<name>_eui.txt` (item profits).
Only the small `example*` fixtures ship with the repository. The benchmark data is shared with the
other HUSPM projects and lives in its own repository, [huspm-datasets][ds]; fetch it with:

```bash
bash fetch_datasets.sh          # download, extract into datasets/, verify checksums
```

Pull the pinned release rather than regenerating the utilities — `RUNNING.md` explains why the
regenerated bytes may differ.

| Dataset | Sequences | δ | ρ | B | Notes |
|---|---|---|---|---|---|
| SIGN | 730 | 0.005 | 0.03 | 21 | Dense and small; the heaviest per-sequence workload |
| LEVIATHAN | 5,834 | 0.005 | 0.03 | 175 | |
| BIBLE | 36,369 | 0.005 | 0.03 | 1,091 | |
| C8T1S5I8N5K | 47,132 | 0.001 | 0.06 | 2,827 | Synthetic, ~8 items per event — the only dataset that exercises the i-extension branch. Published by SPMF as `data.slen_8.tlen_1.seq.patlen_5.lit.patlen_8.nitems_5000_spmf.txt`; the short name encodes the same generator parameters |
| FIFA | 20,450 | 0.050 | 0.30 | 6,135 | Dense, long sequences; scalability scenario only |
| KOSARAK | 990,002 | 0.015 | 0.30 | 297,000 | Sparse and large; scalability only. Recall is measured by a separate probe, since building an oracle at this size costs about 95 minutes |

The declared `B` values are calibrated so that the absolute suite answers with the same oracle as
the relative one — a comparability choice, recorded in `DatasetCatalog` beside the values.

Neither threshold transfers between datasets: δ too high leaves too few patterns to compare against,
too low exhausts memory, and ρ too loose makes the regularity constraint vacuous — it then stops
selecting anything and the numbers describe a different problem. `DatasetCatalog.java` records the
value used for each dataset, `probe_dataset.sh` checks both conditions on a new one, and
`DeltaProbe` sweeps δ alone.

[ds]: https://github.com/tran-minh-thai/huspm-datasets

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
| S10 | Which seed bound closes which gap? Under `--absolute` the two regularity cells coincide — that axis closes by definition, and the table shows it |
| S11 | Does the baseline comparison hold as the number of update batches grows? (k ∈ {4, 16, 64}) |

Under `--absolute`, the S7 sweep scales the declared `B` by the same multipliers it applies to ρ.
The per-batch-exact variant (`partitionMine`) is not part of the default suite — its verdict is
settled and `--m1` reproduces it.

FIFA and KOSARAK run S1 only — the rest would cost hours at their size.

Every configuration is warmed up once, then measured repeatedly, with the repeat count tiered by how
long a single run takes: 15 repeats under 1 s, 10 up to 10 s, 5 up to 120 s, and 3 beyond that. Short
measurements need more repeats because timing noise is a larger share of them. Runtime is reported as
mean ± standard deviation, peak memory and recall as medians.

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
iteration,runtime_ms,build_ms,incr_ms,disc_ms,peak_mb,hs_count,shs_count,recall,status,
seed_mb,incr_mb,disc_mb,tracked_seed,tracked,oracle_size,oracle_hits,abs_b
```

Beyond the timing and count columns: `seed_mb`/`incr_mb`/`disc_mb` are the peak heap DURING each
phase (levels, not increments — they include state still held from earlier phases, so they do not
sum to `peak_mb`); `tracked_seed`/`tracked` count the patterns the miner HOLDS, a superset of what
it returns; `oracle_size`/`oracle_hits` make precision computable — recall alone counts only the
intersection and cannot show a result that returns more than it should; and `abs_b` is the declared
bound the cell actually ran at, zero in relative mode. Every parameter a table needs is in the row
or in `meta.properties` — nothing has to be looked up in the source.

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
| `minUtilRatio` | δ | `minUtil = δ × totalDbUtility`, recomputed as the database grows |
| `absB` | B | Declared absolute regularity bound (`--absolute`): regular ⇔ recurs at least every B sequences, constant at every batch |
| `maxRegRatio` | ρ | Relative mode only: `maxReg = ρ × numSequences`, recomputed per batch |
| `bufferFactor` | μ | Seed threshold `= μ × minUtil`; 1 for the proposed miner, 0.4 and 0.9 for the baselines |
| `numThreads` | T | Worker threads |

The utility threshold is a ratio in both modes — utility is additive, and the partition lemma
handles its growth exactly. The regularity bound is the one that differs: a ratio that rises with
the database in relative mode, a declared constant in absolute mode.

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

1. Ishita, S.Z., Ahmed, C.F., Leung, C.K. *New approaches for mining regular high utility sequential
   patterns.* Applied Intelligence 52, 3781–3806 (2022).
   [10.1007/s10489-021-02536-7](https://doi.org/10.1007/s10489-021-02536-7) — RHusp / RIncHusp, the
   baseline this work extends.
2. Yin, J., Zheng, Z., Cao, L. *USpan: An Efficient Algorithm for Mining High Utility Sequential
   Patterns.* KDD 2012, 660–668. [10.1145/2339530.2339636](https://doi.org/10.1145/2339530.2339636)
3. Gan, W., Lin, J.C.-W., Zhang, J., Fournier-Viger, P., Chao, H.-C., Yu, P.S. *Fast Utility Mining
   on Sequence Data.* IEEE Trans. Cybernetics 51(2), 487–500 (2021).
   [10.1109/TCYB.2020.2970176](https://doi.org/10.1109/TCYB.2020.2970176) — HUSP-ULL.
4. Wang, J.-Z., Huang, J.-L. *On Incremental High Utility Sequential Pattern Mining.* ACM TIST 9(5),
   1–26 (2018). [10.1145/3178114](https://doi.org/10.1145/3178114) — IncUSP-Miner+.

## Citing this work

`CITATION.cff` holds the machine-readable metadata; GitHub renders it under *Cite this repository*.
The datasets are pinned separately — cite the release you used from [huspm-datasets][ds], not a
regenerated copy. The paper describing the method is in preparation; this section will name it once
it appears.
