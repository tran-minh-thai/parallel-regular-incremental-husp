package test;

import java.util.ArrayList;
import java.util.List;

/**
 * Datasets and mining parameters for the experiment suite.
 *
 * <p>This is the only file to edit when adding a dataset or changing a threshold; the runner
 * ({@link ExperimentOfficial}) reads the catalog and needs no changes. Each dataset is one
 * {@code add(...)} line — comment it out to skip it. Missing data files are dropped silently by
 * {@link DatasetSpec#onlyAvailable}, so a partial checkout still runs.
 *
 * <p>Both thresholds are ratios of the data rather than absolute values, so they carry across
 * datasets of different sizes:
 * <ul>
 *   <li>{@code minUtilRatio} (delta) — minUtil = delta x total database utility;</li>
 *   <li>{@code maxRegRatio} (rho) — maxReg = rho x number of sequences.</li>
 * </ul>
 *
 * <p>The trailing boolean marks a dataset as scalability-only: it then runs scenario S1 and skips
 * the comparison and parameter sweeps, which on the largest inputs would dominate the suite's
 * runtime without adding much. Batch splitting is not fixed here either — the runner iterates over
 * the four batch distributions in {@link ExpConfig}, and the scenario named below is only a default.
 *
 * <h2>Choosing delta</h2>
 *
 * <p>Delta does not transfer between datasets. It has to be low enough to return a useful number of
 * patterns and high enough for the run to fit in memory, and density decides where that balance
 * falls: on a dense dataset the pattern space grows steeply as delta drops, while a sparse one
 * barely responds. Measured pattern counts, for reference when revisiting these values:
 *
 * <pre>
 *   delta     SIGN   LEVIATHAN   BIBLE   KOSARAK
 *   0.050        -           -       5         5
 *   0.030       30        1036      58         6
 *   0.025      158           -       -         -
 *   0.020      813        3320     201        19
 *   0.015     4984           -       -        30
 *   0.010        -       22417    1349         -
 * </pre>
 *
 * <p>Pattern count alone does not decide the value: memory has to be checked on the
 * <em>B-Increasing</em> batch split, which is the worst case. There the initial database is only
 * 10% of the data, so the discovery phase must mine the remaining 90% at a rich threshold. SIGN
 * shows how sharp that limit can be — see the note on its line.
 *
 * <p>To retune a dataset, sweep it with {@link DeltaProbe} (or {@link KosarakDeltaProbe} for
 * KOSARAK, which has memory guards), on the B distribution rather than the default A.
 */
public final class DatasetCatalog {
    private DatasetCatalog() {}

    /** Small datasets used by {@code --test}: a few sequences each, so the whole suite runs in seconds. */
    public static List<DatasetSpec> testSuite() {
        List<DatasetSpec> s = new ArrayList<>();
        s.add(new DatasetSpec("example",  0.10, 0.60, ExpConfig.SCEN_A));   // running example, 9 sequences
        s.add(new DatasetSpec("example2", 0.10, 0.60, ExpConfig.SCEN_A));   // 12 short sequences
        return DatasetSpec.onlyAvailable(s);
    }

    /** The benchmark suite reported in the paper. */
    public static List<DatasetSpec> officialSuite() {
        List<DatasetSpec> s = new ArrayList<>();

        // Dense, 730 sequences. 0.03 is a practical lower bound rather than a preference: the
        // pattern count does rise quickly below it, but so does the memory the B-Increasing split
        // needs, and peak memory roughly multiplies by ten per 0.001 step (0.030: 111 MB,
        // 0.029: 1.2 GB, 0.028: over 4 GB). No lower value completes the suite; at 0.03 every
        // scenario runs and recall is 1.0 on all four distributions.
        s.add(new DatasetSpec("SIGN", 0.030, 0.30, ExpConfig.SCEN_A));

        // 5,834 sequences. 0.02 is the richest value that stays light (3,320 patterns, ~215 MB).
        // 0.01 also completes but needs ~1.8 GB and 13 s per run, which is too slow for the
        // 64-batch re-mining baseline in S8.
        s.add(new DatasetSpec("LEVIATHAN", 0.020, 0.30, ExpConfig.SCEN_A));

        // 36,369 sequences. 0.01 gives 1,349 patterns in ~2.5 s and ~265 MB. Higher values leave
        // too few patterns to compare against (0.05 returns only five).
        s.add(new DatasetSpec("BIBLE", 0.010, 0.30, ExpConfig.SCEN_A));

        // 20,450 sequences, dense and long (up to 100 events). Scalability-only: a full run takes
        // several minutes, so the sweeps would dominate the suite.
        s.add(new DatasetSpec("FIFA", 0.050, 0.30, ExpConfig.SCEN_A, true));

        // 990,002 sequences; needs -Xmx16g or more. Sparse, so lowering delta buys few patterns:
        // 0.015 yields 30, and going lower costs far more than it returns. That is still enough
        // for the mining phase to have work to parallelise — at 0.05 (five patterns) the runtime
        // was dominated by loading and speedup stalled near 1.6x. Scalability-only, and recall is
        // skipped because the dataset exceeds ExpConfig.coverageMaxN, so no oracle is built.
        s.add(new DatasetSpec("KOSARAK", 0.015, 0.30, ExpConfig.SCEN_A, true));

        // --- Pending: two datasets chosen to close real gaps in the current five ---------------
        //
        // BMS1 (59,601 sequences, 497 items, average length 2.51) covers the short-sequence end.
        // Nothing else here comes close: the next shortest is KOSARAK at 8.10.
        //
        // C8T1S5I8N5K (47,133 sequences, 68,240 items, 7.97 items per event) is the only dataset
        // in the release whose events hold more than one item. In the other six, and therefore in
        // every number this study has published so far, an event is a singleton — which means the
        // i-extension branch of the miner has never been exercised by an experiment. That is a
        // wider hole than the dataset count.
        //
        // Both stay commented out until probed. Guessing delta is how a dataset ends up measured
        // outside the band where the regularity constraint binds, and MSNBC showed how quiet that
        // failure is: it loaded, ran fast, reported full recall, and returned 196 patterns at
        // rho = 0.01, 0.02, 0.05 and 0.30 alike, because with 17 items over sequences averaging 13
        // events the real gaps are 1 to 3 while rho*N is already 318 at rho = 0.01. Constraint
        // never binds, so the numbers would have described plain high-utility mining.
        //
        // Probe on the benchmark machine, then fill in the delta the sweep supports:
        //     ./probe_dataset.sh BMS1
        //     ./probe_dataset.sh C8T1S5I8N5K
        // The pattern count MUST rise as rho loosens; a flat column disqualifies the dataset.
        //
        // s.add(new DatasetSpec("BMS1",        0.0??, 0.30, ExpConfig.SCEN_A));
        // s.add(new DatasetSpec("C8T1S5I8N5K", 0.0??, 0.30, ExpConfig.SCEN_A));

        return DatasetSpec.onlyAvailable(s);
    }
}
