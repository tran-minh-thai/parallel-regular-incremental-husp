# Running the experiments

How to reproduce the reported numbers, in the order you would do it. `README.md` covers what the
project is; this covers running it.

## 1. Before you start

You need a JDK (11 or later). Maven is optional — there are no runtime dependencies, so `javac`
works offline.

The benchmark datasets are not in this repository. Pull them from the pinned release of
[huspm-datasets](https://github.com/tran-minh-thai/huspm-datasets) into `datasets/`:

```bash
VERSION=v1-seed42-lognormal
curl -fLO https://github.com/tran-minh-thai/huspm-datasets/releases/download/$VERSION/huspm-datasets-$VERSION.tar.gz
tar xzf huspm-datasets-$VERSION.tar.gz -C datasets/
shasum -a 256 -c MANIFEST.sha256        # Linux: sha256sum -c MANIFEST.sha256
```

**Pull the release; do not regenerate the data.** The utilities are synthetic, and although
`SPMF_Converter` fixes its seed at 42, it draws every file from one shared random stream in list
order — so converting a different set of files, or the same files in a different order, yields
different utilities and therefore different numbers than the ones published here. The release is
the only way to reproduce them, which is why it is versioned by the parameters that produced it.

Only the four tiny `example*` files stay in `datasets/` under version control. They are test
fixtures for `--test`, not benchmark data, and they are deliberately not part of the release.

Missing datasets are skipped rather than failing the run, so a partial set still works.

**Set the heap well below physical RAM.** On a 32 GB machine use 16g, not 32g. If the heap
approaches RAM the JVM will swap rather than fail: every timing becomes meaningless and the swap
file can fill the disk. 16g is enough for the whole suite — KOSARAK, the largest, peaks near 1 GB at
its configured threshold.

**On a laptop, plug in and turn off power saving.** The first log line reports the thread sweep:

```
### thread sweep (pinned) = [1, 2, 4, 8, 10] | best T = 10 ###
```

If it warns that the sweep was truncated, the machine is reporting fewer cores than it has — usually
low-power mode. Stop, fix the power settings, and start again, or the scalability numbers are not
comparable.

Also check free disk space (`df -h /`): a suite run writes logs continuously and the JVM may need
swap headroom.

## 2. Build

```bash
javac --release 11 -d out $(find src/main/java -name '*.java')
```

Or `mvn compile`, which puts classes in `target/classes/` instead.

## 3. Smoke test — do not skip this

Runs every scenario on the tiny example datasets in about three minutes. It is the only cheap way to
find out whether something is broken before committing hours to a real run.

```bash
java -Xmx2g -cp out test.ExperimentOfficial --test 2>&1 \
  | grep -E "S10-exactness|S3 HS invariance|S9-musweep|DONE"
```

Check these, and stop if any fails:

| Output | Expected |
|---|---|
| `S10-exactness  P-RIncHUSP[reg,disc]` | recall `1.0000` — both bounds together are exact |
| `S10-exactness  P-RIncHUSP[reg,-]` and `[-,disc]` | recall below 1 — neither bound alone suffices |
| `S9-musweep`, all six values of μ | recall `1.0000` throughout — correctness does not depend on μ |
| `S3 HS invariance across T` | `OK` — the thread count does not change the result |

## 4. Run the suite

```bash
java -Xmx16g -cp out test.ExperimentOfficial              # every dataset
java -Xmx16g -cp out test.ExperimentOfficial --only=SIGN  # one dataset, or a comma-separated list
java -Xmx16g -cp out test.ExperimentOfficial --resume     # continue an aborted run
```

Expect roughly two to four hours on ten cores. Or use the wrappers, which build, log to both console
and file, and on macOS keep the machine awake:

```bash
./run_experiments.sh              # --help lists the options
./run_experiments.sh --no-maven   # javac instead of Maven; no network needed
./run_experiments.sh --resume
nohup ./run_experiments.sh --no-maven > run.log 2>&1 &   # long runs
tail -f results/run_log_all_*.txt
```

On Windows, `run_experiments.ps1` (PowerShell) or `RUN_ALL_WINDOWS.bat` take the same role.

`--only` keeps each dataset's own settings, so it is the right way to re-measure one dataset after
changing its threshold: merge the result over the earlier full run rather than repeating everything
(see step 6).

If the run is interrupted, `--resume` finds the newest directory without a `DONE` marker, skips the
cells already finished, and completes the rest. It is safe to call repeatedly.

## 5. Results

Each run creates a self-describing directory:

```
results/run_<timestamp>_<confighash>/
  results.csv           one row per measured run; completed cells only
  meta.properties       environment + full configuration + config.signature + status
  DONE                  present only if the whole suite finished
  completed.txt         resume bookkeeping
  datasets_done.txt
results/run_log_all_<timestamp>.txt    console log
```

`./results_status.sh` lists every run directory as complete or aborted.

Two rules for keeping results straight. A directory without `DONE` is incomplete: finish it with
`--resume` or delete it. And `config.signature` identifies the configuration that produced the
numbers — if you change a threshold or the suite, the signature changes, and results from different
signatures must never be mixed in one table. `--resume` refuses to continue a run whose signature no
longer matches, so an old run cannot be silently extended by a newer, different version.

Sanity checks once a run finishes:

| Check | Expected |
|---|---|
| Recall | `1.0000` everywhere it is measured; KOSARAK skips it by design |
| S10 | `[reg,disc]` exact; each bound alone misses patterns |
| S9 | recall `1.0000` at every μ; time U-shaped with its minimum at μ = 1 |
| S8 | P-RIncHUSP nearly flat in the batch count, re-mining linear — they cross around k = 3 to 8 |
| S11 | the same crossover seen against every baseline: on LEVIATHAN re-mining wins at k = 4 and loses by roughly 8x at k = 64 |
| S7 | P-RIncHUSP `1.0000` at every ρ; the approximate variant falls away as ρ tightens |
| S5 | P-RIncHUSP and the inverted-index variant agree on patterns and recall, and differ only in time |

## 6. Retuning a threshold

δ has to be chosen per dataset: too high and there are too few patterns to compare against, too low
and the run exhausts memory. Sweep it and watch both:

```bash
# Small and medium datasets. The last argument picks the batch distribution — use B, which leaves
# only 10% of the data in D_old and is the memory worst case.
java -cp out test.DeltaProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.03 0.30 8 B

# KOSARAK has its own probe: at 990k sequences a bad threshold can exhaust memory, so the sweep is
# bounded and stops early.
java -Xmx16g -cp out test.KosarakDeltaProbe
```

Both report the pattern count from the miner itself rather than building a ground-truth oracle,
which would cost far more than the mining.

After choosing a value, set it in `DatasetCatalog.java`, re-run that dataset with `--only`, and — if
you are regenerating the paper's tables — update the matching entry in the table generator.

`DatasetCatalog.java` documents the measured range behind each current value. Two are worth reading
before changing them: SIGN is floored at 0.03, because peak memory on the B-Increasing split
multiplies roughly tenfold per 0.001 step below it; and KOSARAK sits at 0.015 because it is sparse,
and at higher thresholds it returns so few patterns that runtime is dominated by loading and
parallelism has nothing to work on.

## 7. A note on the fully-incremental variant

`partitionMine` mines every batch once, at its own natural threshold, which makes the result exact
after each batch rather than only when queried. It is not part of the suite: with many batches the
candidate set grows and it can be killed by the operating system, which would take a multi-hour run
down with it. Run it separately:

```bash
java -Xmx16g -cp out test.KBench datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.03 0.30 SIGN 10 0.25
```

Both variants are exact; the finer partition costs roughly three times more at four batches. Coarser
partitions admit fewer candidates, because a small part has a small absolute threshold and so accepts
patterns that are locally strong but globally negligible.
