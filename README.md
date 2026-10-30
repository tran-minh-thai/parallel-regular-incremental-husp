# P-RIncHUSP

**Parallel incremental mining of regular high-utility sequential patterns**

A Java implementation of P-RIncHUSP: it mines high-utility sequential patterns that also occur
*regularly*, meaning no gap between occurrences is larger than a bound, over a database that keeps
growing, without re-mining the history on every update.

The regularity bound comes in two modes. In the **absolute** mode (`--absolute`, the mode the study
reports) each dataset declares a constant `B`: a pattern is regular when it recurs at least every
`B` sequences, at every point in time. `B` is given, like the utility threshold, and is never
derived from any database size. The **relative** mode (`maxReg = ρ·N`, the definition inherited from the
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
re-mining is linear in it, so past a small crossover (below two batches on BIBLE, between two and
eight on the others) the incremental method is also the faster one. At thirty-two batches it leads
the same parallel engine re-mining every batch by 14.3x (SIGN), 7.5x (LEVIATHAN), 37.1x (BIBLE) and
11.5x (C8T1S5I8N5K), and the gap keeps widening with the batch count.

Under the declared bound, peak memory is comparable to per-batch re-mining on the reported
configurations: equal on SIGN and C8T1S5I8N5K at sixty-four batches, within about 1.5x on
LEVIATHAN and BIBLE. The limiting case is dense data under a loose bound, where four cells of the suite
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

Wrapper scripts handle building, logging, and (on macOS) keeping the machine awake:

```bash
./run_experiments.sh --absolute   # the reported suite; --help lists every option
./run_experiments.sh --no-maven   # build with javac rather than Maven, so no network is needed
./results_status.sh               # which run directories are complete
```

`run_experiments.ps1` and `RUN_ALL_WINDOWS.bat` do the same on Windows. `RUNNING.md` covers the
workflow in more detail, including how to retune a threshold.

Two things are worth getting right, because they make the numbers wrong rather than merely slow.
Keep the heap well below physical RAM: if it approaches RAM the JVM swaps instead of failing, which
distorts every timing and can fill the disk. On a laptop, plug in and disable power saving, since the
first log line reports the thread sweep, and if it says the sweep was truncated then the machine is
not offering all of its cores.

## Query-time experiments and the measurement campaign

These tools answer a different question from the suite above: when the result is queried after a batch, is
discovery from the anchor (bounded by a cost budget, with an exact fallback) cheaper than re-mining, and is it
always exact? Every tool is standard-library Python 3 or Java and runs on Windows, macOS and Linux.

| Command | What it does |
|---|---|
| `python3 tools/run_tests.py --compile` | The test suite: tool self-tests, randomised exactness of every query policy against brute force on tiny databases, and every deliberate defect with the outcome it must produce (about 1 hour) |
| `python3 tools/fetch_datasets.py [--library DATA_ROOT]` | Fetch the benchmark datasets of the pinned `huspm-datasets` release (from GitHub, or from a local copy under `DATA_ROOT` whose archive and manifest must match the release digests) and verify them against that release's manifest |
| `python3 tools/build_all_datasets.py DATA_ROOT [--verify-only]` | Build the measured datasets (Valued Shoppers chains, H&M, REES46, Dunnhumby, Online Retail II, Ta-Feng) from the raw files, read at the paths under `DATA_ROOT` listed in the tool's `RAW` table, and accept the build only when every output matches the committed manifest in `tools/stream_datasets/manifests/`; `--verify-only` checks the raw files' SHA-256 and stops |
| `python3 tools/machine_info.py --write` | Record the measurement machine in `MEASUREMENT_MACHINE.json` |
| `python3 tools/campaign.py lock` / `run --exp E2` / `status` | Lock the Java build, run the cells of an experiment one JVM at a time, report progress; an interrupted run continues where it stopped |
| `python3 tools/summarize_campaign.py [--write-repeats]` | Per dataset and schedule: total cost of re-mining (F) and of cost-capped discovery (BUD3), their ratio, the checks of the pre-registered hypotheses, and the cells close to a tie that need repetitions |
| `python3 tools/campaign.py --config experiments/campaign_9950x_followup.json run --exp E2G-raw --exp E2G-live --exp E2L --exp E2N --interleave` | The experiments run after the main campaign: BUD3 against BUD3-live on schedule S2 (alternating per dataset), VS-CHAIN46-CW schedule S1 with a limit its re-mine fits in, and the two chains rebuilt without store-type accounts |
| `python3 tools/summarize_campaign.py --config experiments/campaign_9950x_followup.json --exp E2L --exp E2N --exp E2G-raw` | The same checks as for E2 on those experiments, written to `summary_E2L_E2N_E2G-raw.tsv` |
| `python3 tools/check_runbook_blocks.py RUNBOOK REPORT_PS1 SCRATCH_DIR` | Run the measurement machine's command blocks verbatim under PowerShell with fake tools on throw-away git repositories, and check what each step does, including interrupted and failing runs |
| `python3 tools/summarize_heap_guard.py [--pair DATASET RAW_DIR LIVE_DIR]` | Per dataset: BUD3 aborts and their cause, the heap after the collection that follows each abort, the live guard's collections, totals against F, and the checks of the heap guard hypotheses |

The cells (datasets, thresholds, regularity bounds, query schedules, policies, repetitions, time limits) are
defined in `experiments/campaign_9950x.json`; the raw files, their hashes and the role of every column are
documented in `tools/build_stream_datasets.py` and the manifests. A single run outside the campaign:

```bash
java -Xmx16g -Ddold=0.5 -Doracle=first -cp out test.QueryScheduleProbe \
     datasets/VS-CHAIN96-CW_seq.txt datasets/VS-CHAIN96-CW_eui.txt 0.0005 17592 21 every remine-nogc,bud3
```

BUD3 aborts a discovery when the used heap exceeds 60% of the maximum heap. That reading (`totalMemory -
freeMemory`) includes garbage not yet collected. The policy `bud3live` differs from `bud3` only there: on a
breach one worker collects and re-reads, and the discovery is aborted only if the heap is still above the guard.
Both write, per query, the guard, the heap when the attempt started and when it was aborted, the heap after the
collection that follows every abort, the collections the live guard forced, and the abort cause (`heap`,
`deadline`, `oom`).

Results:

- `results-probe/` holds development-machine feasibility runs. Their timings decide what is worth building and
  are not reported.
- `results/9950x/` holds the measurement campaign: `cells.tsv` (one row per finished cell: status, run
  directory, commit, Java build), one directory per cell with `run_meta.json` (command, commit, tree state,
  source and data hashes, Java, heap, threads), `exactness.csv` (per query: patterns returned, missing, extra,
  value mismatches, the policy's action) and `timing_feasibility.csv` (per query: ingest and query time), the
  console log of every cell under `logs/`, `ENV_LOCK.json` and `summary_E2.tsv`.

`COMMIT_MAP.tsv` maps commit ids that changed when manuscript tables were removed from the unpublished history;
artifacts that cite an old id are resolved through it.

## Datasets

`datasets/` holds `<name>_seq.txt` (quantitative sequences) and `<name>_eui.txt` (item profits).
Only the small `example*` fixtures ship with the repository. The benchmark data is shared with the
other HUSPM projects and lives in its own repository, [huspm-datasets][ds]; fetch it with:

```bash
bash fetch_datasets.sh          # download, extract into datasets/, verify checksums
```

Pull the pinned release rather than regenerating the utilities; `RUNNING.md` explains why the
regenerated bytes may differ.

| Dataset | Sequences | δ | ρ | B | Notes |
|---|---|---|---|---|---|
| SIGN | 730 | 0.005 | 0.03 | 21 | Dense and small; the heaviest per-sequence workload |
| LEVIATHAN | 5,834 | 0.005 | 0.03 | 175 | |
| BIBLE | 36,369 | 0.005 | 0.03 | 1,091 | |
| C8T1S5I8N5K | 47,132 | 0.001 | 0.06 | 2,827 | Synthetic, ~8 items per event; the only dataset that exercises the i-extension branch. Published by SPMF as `data.slen_8.tlen_1.seq.patlen_5.lit.patlen_8.nitems_5000_spmf.txt`; the short name encodes the same generator parameters |
| FIFA | 20,450 | 0.050 | 0.30 | 6,135 | Dense, long sequences; scalability scenario only |
| KOSARAK | 990,002 | 0.015 | 0.30 | 297,000 | Sparse and large; scalability only. Recall is measured by a separate probe, since building an oracle at this size costs about 95 minutes |

The declared `B` values are calibrated so that the absolute suite answers with the same oracle as
the relative one. This is a comparability choice, recorded in `DatasetCatalog` beside the values.

Neither threshold transfers between datasets: δ too high leaves too few patterns to compare against,
too low exhausts memory, and ρ too loose makes the regularity constraint vacuous, because it then stops
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
| S4 | Is it stable across batch distributions (uniform, increasing, oscillating, decreasing)? |
| S5 | What does the maintenance strategy cost, against a per-pattern inverted index? |
| S6 | How sensitive is it to the utility threshold δ? |
| S7 | How sensitive is it to the regularity threshold ρ, and where does the approximate variant fail? |
| S8 | Past how many batches does incremental maintenance beat re-mining? |
| S9 | Does the seed-split factor affect correctness, and where is it cheapest? |
| S10 | Which seed bound closes which gap? Under `--absolute` the two regularity cells coincide, so that axis closes by definition and the table shows it |
| S11 | Does the baseline comparison hold as the number of update batches grows? (k ∈ {4, 16, 64}) |

Under `--absolute`, the S7 sweep scales the declared `B` by the same multipliers it applies to ρ.
The per-batch-exact variant (`partitionMine`) is not part of the default suite; its verdict is
settled and `--m1` reproduces it.

FIFA and KOSARAK run S1 only, because the rest would cost hours at their size.

Every configuration is warmed up once, then measured repeatedly, with the repeat count tiered by how
long a single run takes: 15 repeats under 1 s, 10 up to 10 s, 5 up to 120 s, and 3 beyond that. Short
measurements need more repeats because timing noise is a larger share of them. Runtime is reported as
mean ± standard deviation, peak memory and recall as medians.

### One naming difference between the code and the paper

The code calls the proposed miner's seed-split factor `mu` throughout: the CSV column, the
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
phase (levels, not increments; they include state still held from earlier phases, so they do not
sum to `peak_mb`); `tracked_seed`/`tracked` count the patterns the miner HOLDS, a superset of what
it returns; `oracle_size`/`oracle_hits` make precision computable, since recall alone counts only the
intersection and cannot show a result that returns more than it should; and `abs_b` is the declared
bound the cell actually ran at, zero in relative mode. Every parameter a table needs is in the row
or in `meta.properties`, so nothing has to be looked up in the source.

`./results_status.sh` lists each run directory as complete or aborted. Runs whose
`config.signature` differs came from different configurations; never mix their numbers.

## Algorithms and baselines

| Class | In the paper | R / I / P | Role |
|---|---|---|---|
| `AlgoPRIncHUSP` | P-RIncHUSP | ✓ / ✓ / ✓ | Proposed |
| `AlgoPRIncHUSP` (T=1) | P-RIncHUSP-seq | ✓ / ✓ / - | Same engine on one thread; the speedup reference |
| `AlgoRIncHUSP` | RIncHusp Fix(μ) | ✓ / ✓ / - | The sequential incremental predecessor |
| `AlgoRHUSP`, `AlgoRHUSPRecompute` | RHusp | ✓ / - / - | Static mining; the ground truth for recall |
| `AlgoRHUSPMinerParallel` | ParRemine-RDLB | ✓ / - / ✓ | Parallel static engine: seeds `D_old`, and re-mines as a baseline |
| `AlgoRemine`, `AlgoParRemine` | Remine-static | ✓ / - / ✓ | Re-mine on every batch |
| `USpanAlgorithm`, `HUSPULLAlgorithm`, `IncUSPMinerPlusAlgorithm` | - | - | High-utility mining without the regularity constraint |

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

The utility threshold is a ratio in both modes, because utility is additive and the partition lemma
handles its growth exactly. The regularity bound is the one that differs: a ratio that rises with
the database in relative mode, a declared constant in absolute mode.

## Layout

```
src/main/java/
  algorithms/   miners, data structures, baselines   (see algorithms/README.md)
  common/       sequence model used by the reference baselines
  test/         experiment harness: runners, configuration, probes
datasets/       example data; RUNNING_EXAMPLE.md works through it by hand
tools/          dataset builders, test suite, campaign runner and summaries (Python, standard library)
experiments/    campaign definition
```

`algorithms/README.md` describes the role of every class and explains the exactness argument in more
detail.

## References

1. Ishita, S.Z., Ahmed, C.F., Leung, C.K. *New approaches for mining regular high utility sequential
   patterns.* Applied Intelligence 52, 3781–3806 (2022).
   [10.1007/s10489-021-02536-7](https://doi.org/10.1007/s10489-021-02536-7). RHusp / RIncHusp, the
   baseline this work extends.
2. Yin, J., Zheng, Z., Cao, L. *USpan: An Efficient Algorithm for Mining High Utility Sequential
   Patterns.* KDD 2012, 660–668. [10.1145/2339530.2339636](https://doi.org/10.1145/2339530.2339636)
3. Gan, W., Lin, J.C.-W., Zhang, J., Fournier-Viger, P., Chao, H.-C., Yu, P.S. *Fast Utility Mining
   on Sequence Data.* IEEE Trans. Cybernetics 51(2), 487–500 (2021).
   [10.1109/TCYB.2020.2970176](https://doi.org/10.1109/TCYB.2020.2970176). HUSP-ULL.
4. Wang, J.-Z., Huang, J.-L. *On Incremental High Utility Sequential Pattern Mining.* ACM TIST 9(5),
   1–26 (2018). [10.1145/3178114](https://doi.org/10.1145/3178114). IncUSP-Miner+.

## Citing this work

`CITATION.cff` holds the machine-readable metadata; GitHub renders it under *Cite this repository*.
The datasets are pinned separately; cite the release you used from [huspm-datasets][ds], not a
regenerated copy. The paper describing the method is in preparation; this section will name it once
it appears.
