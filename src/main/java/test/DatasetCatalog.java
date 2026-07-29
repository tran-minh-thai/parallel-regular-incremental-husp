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
 * <p>To retune a dataset, sweep candidate thresholds with {@link DeltaProbe} (or
 * {@link KosarakDeltaProbe} for KOSARAK, which has memory guards) on the B-Increasing distribution
 * rather than the default A: the increasing split is where a threshold that looks safe under A runs
 * out of memory, so it is the split that decides. {@code probe_dataset.sh} wraps the whole check.
 */
public final class DatasetCatalog {
    private DatasetCatalog() {}

    /** Small datasets used by {@code --test}: a few sequences each, so the whole suite runs in seconds. */
    public static List<DatasetSpec> testSuite() {
        List<DatasetSpec> s = new ArrayList<>();
        s.add(new DatasetSpec("example",  0.10, 0.60, ExpConfig.SCEN_A, 5));   // running example, 9 sequences
        s.add(new DatasetSpec("example2", 0.10, 0.60, ExpConfig.SCEN_A, 7));   // 12 short sequences
        return DatasetSpec.onlyAvailable(s);
    }

    /** The benchmark suite reported in the paper. */
    public static List<DatasetSpec> officialSuite() {
        List<DatasetSpec> s = new ArrayList<>();

        // delta and rho were probed together, not independently: they interact, because a tight rho
        // lets the regularity pruning shrink the search space, which in turn makes a low delta
        // affordable. The three text datasets share (delta 0.005, rho 0.03); the sweep results that
        // fixed those values are in results of ./probe_dataset.sh. rho = 0.03 is the important change
        // from the earlier 0.30: at 0.30 the regularity constraint pruned nothing on these datasets
        // (S7 was flat, 30 -> 31 on SIGN across the whole sweep), so the numbers described plain
        // high-utility mining. At 0.03 the constraint is active — tightening to 0.02 drops SIGN from
        // 830 patterns to 172, loosening to 0.04 raises it to 4,439.

        // Dense, 730 sequences. At (0.005, 0.03): 830 patterns, ~100 MB, under a second. The old
        // 0.03/0.30 gave 30 patterns, too few, and its memory wall (ten-fold per 0.001 step) was an
        // artifact of rho = 0.30 leaving the search unpruned, not a property of the data.
        // absB values below are DECLARED constants for the --absolute suite. They are calibrated to
        // coincide with the relative study's bound at its final size, so the two suites answer with
        // the same oracle and stay comparable -- that is a benchmarking choice, stated here once;
        // the algorithms receive B as a given number and never derive it from any database size.
        s.add(new DatasetSpec("SIGN", 0.005, 0.03, ExpConfig.SCEN_A, 21));

        // 5,834 sequences. At (0.005, 0.03): 5,148 patterns, ~220 MB, ~2 s.
        s.add(new DatasetSpec("LEVIATHAN", 0.005, 0.03, ExpConfig.SCEN_A, 175));

        // 36,369 sequences. At (0.005, 0.03): 2,141 patterns, ~350 MB, ~2.4 s.
        s.add(new DatasetSpec("BIBLE", 0.005, 0.03, ExpConfig.SCEN_A, 1091));

        // 47,133 sequences, IBM Quest synthetic. This is the one full-suite dataset whose events hold
        // more than one item (7.96 on average, up to 23), so it is the only one that exercises the
        // i-extension branch of the miner — every other dataset here has singleton events. rho is its
        // own, 0.06 rather than 0.03, because the same ratio maps to a different absolute maxReg: the
        // planted patterns' periods cluster near maxReg = 0.055*N, so the count jumps from 62 at
        // rho = 0.05 to 5,612 at 0.06 and then saturates. At (0.001, 0.06): 5,612 patterns, ~1.2 s.
        // Added to s6Datasets below so it runs S5-S11, not only S1.
        s.add(new DatasetSpec("C8T1S5I8N5K", 0.001, 0.06, ExpConfig.SCEN_A, 2827));

        // 20,450 sequences, dense and long (up to 100 events). Scalability-only: even at a probed
        // threshold it costs ~76 s per run at T=1, which the batch-re-mining baselines in S8/S11
        // multiply by the batch count. It is a real dataset of medium size, so it earns its place on
        // the S1 speedup curve, but a full run would dominate the suite for one more s-extension
        // point, and s-extension is already covered by SIGN/LEVIATHAN/BIBLE.
        s.add(new DatasetSpec("FIFA", 0.050, 0.30, ExpConfig.SCEN_A, true, 6135));

        // 990,002 sequences; needs -Xmx16g or more. Scalability-only for two independent reasons: it
        // exceeds ExpConfig.coverageMaxN so no oracle can be built, which the correctness scenarios
        // need; and it is sparse enough that delta = 0.015 yields only 30 patterns (at 0.05 just five,
        // where loading dominates and speedup stalled near 1.6x), below which cost rises faster than
        // the count returns.
        s.add(new DatasetSpec("KOSARAK", 0.015, 0.30, ExpConfig.SCEN_A, true, 297000));

        // --- Datasets evaluated for the i-extension gap and rejected, kept as a record ----------
        //
        // The search for a real multi-item-event dataset found that real sequence data with large
        // itemsets carries one of two intractable traits. ONLINE_RETAIL_II_ALL has itemsets up to 275
        // items (2^275 subsets to enumerate per itemset). MICROBLOG_PCU has itemsets of a workable 8
        // but sequences averaging 510 items, and did not finish a single configuration in three
        // minutes even at a high threshold. MSNBC's regularity constraint never binds (196 patterns at
        // rho 0.01 through 0.30) because its 17 items recur in nearly every sequence. BMS1's operating
        // band is pinched shut: high delta returns nothing, low delta explodes. So the i-extension
        // branch is covered by the synthetic C8T1S5I8N5K, which is what the field does too — every
        // real dataset in the HUSPM benchmark set (see HUSP-ULL, IEEE TCYB 2021) has one item per
        // itemset, and the only multi-item dataset there is IBM Quest synthetic.

        return DatasetSpec.onlyAvailable(s);
    }
}
