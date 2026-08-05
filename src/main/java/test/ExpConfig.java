package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoParRemine;
import algorithms.AlgoRHUSPMinerParallel;
import algorithms.AlgoRIncHUSP;
import algorithms.AlgoRemine;

/**
 * Configuration for the experiment suite. Every constant shared by more than one scenario lives
 * here, so a run is reproducible from this file together with {@link DatasetCatalog}; the scenarios
 * themselves are described in {@link ExperimentOfficial}.
 *
 * <p>The buffer factor μ needs a word of explanation, because it is a tuning parameter in the
 * sequential line of work this study builds on but not in this one. Those algorithms treat μ as a
 * "semi-high buffer" factor to be chosen or adapted within [0.4, 0.9]. Here it is fixed at
 * {@link #MU_PARTITION} = 1, which makes the seed threshold θ₀ = δ·U(D_old) and the discovery
 * threshold θ_disc = δ·U(ΔD), so each part of the database is mined at its own natural threshold, which
 * is the condition under which the union of the per-part results is complete.
 *
 * <p>Values on either side cost more without buying anything: below 1, the seed threshold drops
 * under that natural point and the seed grows, but discovery would have recovered those patterns
 * regardless; above 1, the threshold rises and the same work moves into a more expensive discovery
 * phase. Scenario S9 sweeps μ and shows both effects, along with recall staying at 1.0 throughout.
 * {@link #muMin} and {@link #muMax} remain only to configure the baselines.
 */
public final class ExpConfig {
    private ExpConfig() {}

    /**
     * Absolute-maxReg mode (--absolute): the regularity constraint is a per-dataset DECLARED constant
     * B -- a pattern is regular when it recurs at least every B sequences, at every point in time.
     * B is part of the problem statement, like delta: a given number, never derived from any database
     * size. With nothing drifting, the pruning a full re-mine enjoys is sound in every phase, and the
     * retention band whose width exhausted the heap under the relative rule does not exist. The
     * benchmark's declared values (DatasetCatalog) are calibrated to coincide with the relative
     * study's final bounds, so both suites answer with the same oracle -- a comparability choice,
     * not a definition. {@link ExperimentOfficial#benchmark} sets {@link #absoluteB} per cell.
     */
    public static boolean absoluteMode = false;
    public static int absoluteB = 0;

    /** θ₀ = μ·δ·U(D_old). μ=1 ⟹ θ₀ = δ·U(D_old) and θ_disc = δ·U(ΔD): the partition-lemma value. */
    public static final double MU_PARTITION = 1.0;

    // ===================== μ buffer band: BASELINES ONLY (reference Table 10: μ₀=0.40, [0.40-0.90]) =====================
    public static double muMin = 0.40;          // RIncHusp Fix(0.4) baseline μ
    public static double muMax = 0.90;          // baseline adaptive ceiling
    public static double muFixHigh = 0.90;      // Fix(high μ) baseline: fast but loses patterns

    // ===================== Benchmark =====================
    public static int  warmupRuns   = 1;

    /**
     * Measured repeats, tiered by how long one run takes. A short run's mean is noisy until it has
     * many samples; a long run's is stable with few, and forcing many would dominate the suite. The
     * thresholds follow the review guidance (CAM_NANG III.4.1): reviewers rejected a flat three-run
     * protocol as unreliable "for measurements lasting only tens of milliseconds", and a flat high
     * count is the opposite waste.
     *
     * <pre>
     *   run time    repeats
     *   &lt; 1 s        15
     *   1-10 s       10
     *   10-120 s      5
     *   &gt; 120 s        3
     * </pre>
     *
     * {@code --test} forces this to 2 via {@link #fixedRepeatsForTest} so the smoke suite stays fast.
     */
    public static Integer fixedRepeatsForTest = null;   // set by --test; null means use the tiers
    public static int repeatsForRuntime(long ms) {
        if (fixedRepeatsForTest != null) return fixedRepeatsForTest;
        if (ms < 1_000)   return 15;
        if (ms < 10_000)  return 10;
        if (ms < 120_000) return 5;
        return 3;
    }
    /** Timeout for one measured run of one algorithm. A run past this is recorded as timed out and
     *  the suite carries on. This is the safety net that stops a pathological configuration from
     *  eating the night. Keep it tight. */
    public static long runTimeoutMs = 60L * 60 * 1000;   // 60 minutes per run
    /**
     * Timeout for building the recall oracle, kept separate from {@link #runTimeoutMs} on purpose.
     * The oracle is single-threaded by design and re-mines the whole database, so it is far slower
     * than any measured run; sharing one limit would force a choice between giving the oracle room
     * and leaving the benchmark runs unguarded. Raise this one (with {@link #coverageMaxN}) to
     * measure recall on a dataset the oracle cannot reach inside the default hour.
     */
    public static long oracleTimeoutMs = 60L * 60 * 1000;

    // ===================== Thread sweep: PINNED (reproducibility, luuy C7) =====================
    /**
     * Thread counts swept in S1, and the source of the "best T" used by S2/S4/S5/S6/S7/S8.
     * <b>PINNED</b> rather than derived from {@code availableProcessors()}: a machine can silently
     * report fewer cores (macOS Low Power Mode did exactly that mid-study), which would quietly change
     * the tables. Entries above the machine's real core count are DROPPED rather than oversubscribed,
     * and {@link #threadSweepTruncated()} makes the runner print a loud warning.
     */
    public static final int[] THREAD_SWEEP = {1, 2, 4, 8, 10};

    /** The pinned sweep restricted to what this machine can actually run. */
    public static int[] effectiveThreadSweep() {
        int cores = Runtime.getRuntime().availableProcessors();
        java.util.List<Integer> ok = new java.util.ArrayList<>();
        for (int t : THREAD_SWEEP) if (t <= cores) ok.add(t);
        if (ok.isEmpty()) ok.add(1);
        int[] a = new int[ok.size()];
        for (int i = 0; i < a.length; i++) a[i] = ok.get(i);
        return a;
    }

    /** "Best T" for the single-configuration scenarios = largest usable entry of the pinned sweep. */
    public static int bestT() {
        int[] s = effectiveThreadSweep();
        return s[s.length - 1];
    }

    /** True when this machine cannot run the full pinned sweep → results deviate from the protocol. */
    public static boolean threadSweepTruncated() {
        return effectiveThreadSweep().length < THREAD_SWEEP.length;
    }

    // ===================== Oracle / recall =====================
    /**
     * Measure recall ONLY WHEN numSequences ≤ this. The oracle is the single-threaded RHusp
     * reimplementation, single-threaded on purpose so that it is independent of the parallel
     * machinery under test, and it re-mines the whole accumulated database at every checkpoint.
     * Past roughly this size that exceeds {@link #runTimeoutMs}: KOSARAK (990,002 sequences) is the
     * one benchmark dataset above the line, so it reports timing without recall. This is a cost
     * limit, not a claim that the result needs no checking; raise both this and the timeout to
     * measure it anyway.
     */
    public static int coverageMaxN = 40000;

    // ===================== 4 batch distribution scenarios (A/B/C/D) =====================
    public static final double[] SCEN_A = {0.25, 0.25, 0.25, 0.25};   // Uniform (stable)
    public static final double[] SCEN_B = {0.10, 0.20, 0.30, 0.40};   // Increasing (accelerating)
    public static final double[] SCEN_C = {0.40, 0.10, 0.40, 0.10};   // Oscillating (bursty)
    public static final double[] SCEN_D = {0.40, 0.30, 0.20, 0.10};   // Decreasing (slowing down)

    /** Names + ratios of the 4 scenarios, used for S4 (distribution robustness). */
    public static final String[]   DIST_NAMES  = {"A-Uniform", "B-Increasing", "C-Oscillating", "D-Decreasing"};
    public static final double[][] DIST_RATIOS = {SCEN_A, SCEN_B, SCEN_C, SCEN_D};

    /**
     * S5, the fine-batch STREAMING regime: D_old = 25% (keeps the seeding threshold sane; a tiny
     * D_old collapses it and OOMs, see the DatasetCatalog SIGN note) + 15 equal 5% increments. Content-driven
     * maintenance amortizes shared-prefix matching across every increment, so its edge over a
     * per-pattern inverted index grows with the number of increments; the 4-batch regimes A-D
     * undersell it. This is also the realistic incremental-mining scenario (many small arrivals on a
     * large base).
     */
    public static final double[] SCEN_FINE = fineRatios(16, 0.25);

    /** D_old = {@code dOld} fraction + (nBatches−1) equal increments. Public: reused by S8 batch-scaling. */
    public static double[] fineRatios(int nBatches, double dOld) {
        double[] r = new double[nBatches];
        r[0] = dOld;
        java.util.Arrays.fill(r, 1, nBatches, (1.0 - dOld) / (nBatches - 1));
        return r;
    }

    // ===================== Scenario on/off switches =====================
    public static boolean runS1Scalability = true;   // P-RIncHUSP sweep over thread count
    public static boolean runS2Compare     = true;   // proposed vs P-RIncHUSP-seq, RIncHusp Fix(μ), and re-mine
    public static boolean runS4Distribution = true;  // 4 distributions A/B/C/D
    public static boolean runS11BatchCompare = true; // S2 comparison repeated at several batch counts
    public static boolean runS5FineBatch   = true;   // fine-batch streaming (isolates the maintain strategy)
    public static boolean runS6DeltaSweep  = true;   // δ-sensitivity sweep (light datasets only)
    public static boolean runS7RhoSweep    = true;   // ρ-sensitivity sweep (light datasets only)
    public static boolean runS8BatchScaling = true;  // batch-count scaling against re-mining
    public static boolean runS9MuSweep     = true;   // buffer factor sweep
    /**
     * S9B repeats the λ sweep under the INCREASING batch distribution instead of the uniform one.
     * It exists to answer a specific question: raising λ lifts θ₀ and lowers the discovery threshold
     * by the same amount, so it shifts memory from seeding into discovery. Under the uniform split
     * seeding dominates and raising λ helps; under the increasing split D_old is smallest and the
     * increment carries almost everything, so discovery dominates and the useful direction should
     * reverse. Off by default: it is a follow-up probe, not part of the reported suite.
     */
    public static boolean runS9BMuSweepDistB = false;

    /**
     * Include P-RIncHUSP-P in the S11 comparison. The two variants answer different questions and
     * cannot be read off one another:
     * <ul>
     *   <li>P-RIncHUSP (coarse, two-part split) is exact when the result is QUERIED. Between queries
     *       it answers from the maintained set alone, at recall 0.93-0.97. Its discovery phase runs
     *       once per query over the whole accumulated increment.</li>
     *   <li>P-RIncHUSP-P (fine, k-part split) is exact after EVERY batch, because each batch is mined
     *       once at its own natural threshold. Total mining work is one pass over the data no matter
     *       how many batches; the price is candidates, since a small part has a small absolute
     *       threshold and admits patterns that are locally strong but globally negligible.</li>
     * </ul>
     * The reported comparison against a baseline that re-mines per batch only holds at equal
     * correctness if the per-batch-exact variant is the one measured, so S11 carries both.
     */
    // SUPERSEDED as a suite member (author's decision, 2026-07-28): the two-part lambda split with
    // query-time discovery is the adopted design, verified exact; this variant answered the M1
    // question and the answer is on record -- never competitive at k=4, out of memory from k=16 on
    // every dataset, under the relative AND the absolute bound alike. Off by default so suite runs
    // stop paying its OOM cells; --m1 can still switch it on to reproduce the verdict.
    public static boolean runS11Mode1 = false;

    /** S11 runs only the two algorithms the per-batch-exact comparison needs. Set by {@link #enableM1Only()}. */
    public static boolean s11Mode1Only = false;

    /**
     * Mine D_new once, as a single batch, with the parallel baseline. This is the cost an application
     * pays when it needs the result only at the end of the batch sequence, and it is the opponent the
     * proposal actually faces in that case. Without it the single-mine cost can only be bounded --
     * from above by the baseline at k=2 and from below by its k=64 total divided by 64 -- and on a
     * dataset where that interval straddles the proposal's runtime no conclusion follows either way.
     */
    public static boolean runS12SingleMine = false;

    /**
     * Run the three follow-up probes together, in one session, and nothing else: the per-batch-exact
     * comparison (S11 restricted), the single full re-mine (S12), and the lambda sweep under the
     * increasing distribution (S9B). They are independent questions but share a machine and a warm-up,
     * so measuring them in one pass keeps them mutually comparable and off the reported suite.
     */
    public static void enableFollowupOnly() {
        runS1Scalability = runS2Compare = runS4Distribution = false;
        runS5FineBatch = runS6DeltaSweep = runS7RhoSweep = runS8BatchScaling = false;
        runS9MuSweep = runS10Exactness = false;
        runS11BatchCompare = true;
        runS11Mode1 = true;
        s11Mode1Only = true;
        runS12SingleMine = true;
        runS9BMuSweepDistB = true;
    }

    /**
     * Run ONLY the per-batch-exact comparison: P-RIncHUSP-P against ParRemine at each batch count.
     * Both are measured in the same session so the comparison does not cross runs. Everything else is
     * switched off, so this fills the gap left by the reported suite without repeating it.
     */
    public static void enableM1Only() {
        runS1Scalability = runS2Compare = runS4Distribution = false;
        runS5FineBatch = runS6DeltaSweep = runS7RhoSweep = runS8BatchScaling = false;
        runS9MuSweep = runS10Exactness = runS9BMuSweepDistB = false;
        runS11BatchCompare = true;
        runS11Mode1 = true;
        s11Mode1Only = true;
    }

    /**
     * Run ONLY the S9B probe: the lambda sweep under the increasing batch distribution. Everything
     * else is switched off so the run finishes in minutes and writes one scenario into results.csv,
     * which keeps it out of the way of the suite the paper reports.
     */
    public static void enableS9BOnly() {
        runS1Scalability = runS2Compare = runS4Distribution = runS11BatchCompare = false;
        runS5FineBatch = runS6DeltaSweep = runS7RhoSweep = runS8BatchScaling = false;
        runS9MuSweep = runS10Exactness = false;
        runS9BMuSweepDistB = true;
    }
    public static boolean runS10Exactness  = true;   // 2x2 ablation of the two seed bounds

    /**
     * Buffer factors swept by S9, where θ₀ = μ·δ·U(D_old). Recall holds at 1.0 at every μ, since
     * exactness does not depend on θ₀. Cost does move with μ, since the seed shrinks as θ₀ rises
     * while discovery has to mine ΔD at a lower threshold, but the shape is dataset-dependent, NOT a U
     * with its floor at μ=1: the measured time minima sit at μ=3 (SIGN), μ=0.4 (LEVIATHAN), μ=1.5
     * (C8T1S5I8N5K) and μ=1 (BIBLE alone), and for peak memory μ=2 is at or below μ=1 on all four.
     * μ=1 is canonical because the partition lemma makes it so, each part being mined at its own
     * natural threshold, not because it is cheapest. 0.4 is the value the sequential baselines use.
     */
    public static final double[] S9_MUS = {0.4, 0.7, 1.0, 1.5, 2.0, 3.0};

    // ===================== Sweep ranges for S6/S7/S8 =====================
    /**
     * δ multipliers applied to each dataset's base δ. All are ≥ 1, so minUtil only rises and the
     * pattern space only shrinks: a sweep can never be heavier than the base configuration, which
     * matters because these run unattended.
     */
    public static final double[] S6_DELTA_MULT = {1.0, 1.5, 2.0, 3.0};
    /**
     * ρ multipliers applied to each dataset's own base ρ (0.03 for the text datasets, 0.06 for
     * C8T1S5I8N5K), sweeping from strict to loose around the reported value.
     *
     * Capped at 1.5, not 2.0. At the new low δ the pattern space is far larger than before, and the
     * exact miner's discovery phase holds the whole of it, so memory climbs steeply with ρ: SIGN at
     * (δ=0.005, ρ=0.05) already needs ~1.9 GB and ρ=0.06 (the old 2.0× point) extrapolates to ~10 GB,
     * enough to risk the definitive run. 1.5× keeps SIGN at ρ=0.045 (~320 MB) while still spanning
     * the informative region (172 -> 830 -> 4439 patterns), and for C8T1S5I8N5K the 0.5×-1.0× range
     * still crosses its saturation cliff. The approximate baselines do not hit this because they do
     * not mine the full space. That memory gap is the cost of exactness, discussed in the
     * limitations rather than hidden by shrinking the sweep further.
     */
    public static final double[] S7_RHO_MULT = {0.5, 1.0, 1.5};
    /**
     * Batch counts for S8, each run as D_old (25%) followed by n−1 increments. The range spans counts
     * small enough for re-mining to still be cheap through counts where it clearly is not, so the two
     * cost curves cross inside the plot: re-mining is linear in the number of updates, incremental
     * maintenance is close to flat.
     * <p>
     * Read the flatness for what it measures. The harness queries once, after the last batch, so a
     * run costs one seeding plus k maintenance steps plus ONE discovery, and only the maintenance
     * term scales with k. A caller that queries after every batch pays discovery every time and the
     * total is no longer flat; {@code newProposedPartition} is the variant for that access pattern,
     * since it mines each batch once as the batch lands.
     */
    public static final int[] S8_BATCH_COUNTS = {2, 4, 8, 16, 32, 64};

    /**
     * Batch counts for S11, the baseline comparison repeated over several update-cycle counts.
     * {@code fineRatios(4, 0.25)} is elementwise equal to {@link #SCEN_A}, so the k=4 column
     * reproduces S2 rather than measuring a different configuration.
     * <p>
     * S11 writes its own scenario tag on purpose: the table generator selects S2 rows without
     * constraining the batch count, so emitting these rows as "S2-compare" would average across
     * all three k and silently corrupt every S2 table.
     */
    public static final int[] S11_BATCH_COUNTS = {4, 16, 64};
    /**
     * Datasets that get the S6-S10 sweeps. The heavy ones are left out: sweeping them would add
     * many hours and the sweeps answer questions the large datasets do not. Not final:
     * {@code --test} swaps in the tiny example datasets so the sweep scenarios can be exercised in
     * seconds before committing to a long run. That check is worth keeping: a scenario that has
     * never run on the small suite has never had its correctness assertions tested at all, and a
     * loose regularity threshold on the real data can easily hide a seeding bug.
     */
    public static java.util.Set<String> s6Datasets =
            new java.util.HashSet<>(java.util.Arrays.asList("SIGN", "LEVIATHAN", "BIBLE", "C8T1S5I8N5K"));

    /** {@code --test}: point the sweeps at the tiny example datasets, and fix repeats at 2 so the
     * smoke suite stays fast rather than doing 15 per sub-second run. */
    public static void enableSweepsForTestSuite() {
        s6Datasets = new java.util.HashSet<>(java.util.Arrays.asList("example", "example2"));
        fixedRepeatsForTest = 2;
    }

    /**
     * Escape hatch for datasets that should run S5 even though they are marked scalability-only.
     * The fine-batch schedule seeds from 25% of the data, so it avoids the memory pressure that
     * usually causes a dataset to be marked that way in the first place. Currently empty: no
     * scalability-only dataset needs S5.
     */
    public static final java.util.Set<String> s5ExtraDatasets = java.util.Collections.emptySet();

    // ===================== Miner factory =====================
    /**
     * Builds the proposed miner on {@code threads} threads.
     *
     * <p>The result is exact: the pattern set equals a full re-mine of the updated database, by
     * construction and as measured on every dataset in the suite. Three settings are responsible, and
     * the first two close independent gaps, so neither alone is enough. S10 ablates them.
     *
     * <ol>
     *   <li>{@code seedPruneByFinalN}: regularity is pruned at ρ·N_final while seeding, the tightest
     *       bound that stays sound. A gap inside D_old survives unchanged when data is appended, and
     *       irregularity is anti-monotone under extension, so pruning at ρ·N_current instead drops
     *       patterns that are irregular within D_old yet regular once the database is complete. The
     *       effect grows as ρ tightens.</li>
     *   <li>{@code discoverExact}: the increment is mined at minUtil − θ₀. Because utility is
     *       additive over a partition, that recovers precisely those patterns too weak in D_old to
     *       have been seeded.</li>
     *   <li>μ = 1: this sets θ₀ = δ·U(D_old) and the discovery threshold to δ·U(ΔD), so each part is
     *       mined at its own natural threshold, which is what the partition lemma requires for the
     *       union of the two to be complete. It is also where cost bottoms out, seeding growing
     *       cheaper as θ₀ rises and discovery more expensive; see {@link #S9_MUS}.</li>
     * </ol>
     *
     * <p>Maintenance uses the content-driven parallel trie: a shared prefix is matched once on behalf
     * of every pattern extending it, and the work is split over disjoint ascending sequence ranges.
     * Its output is verified element-wise against per-pattern re-matching and does not depend on the
     * thread count. D_old itself is seeded by the companion study's parallel engine.
     */
    public static AlgoPRIncHUSP newProposed(int threads) {
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = threads;
        m.seedWithEngine05 = true;                   // seed D_old with the companion parallel engine
        m.trieMaintain = true;                       // content-driven parallel maintenance
        m.seedPruneByFinalN = true;                  // regularity bound at seeding: ρ·N_final
        m.discoverExact = true;                      // recover from ΔD what D_old was too weak to seed
        m.forkSeed = false;                          // in-house enumeration is unused when seeding externally
        // -DfloorPrune lets ANY harness run enable the floor prune without a code edit. Presence of
        // the property is what enables it -- a bare -DfloorPrune sets the EMPTY string, which
        // Boolean.getBoolean would read as false, and that mismatch already produced one probe run
        // that measured nothing. Absent the property this stays false, so every reported
        // configuration is unchanged; the ablation stays out either way, since the miner engages
        // the prune only under a sound ceiling bound.
        String fp = System.getProperty("floorPrune");
        m.floorPruneSeeds = fp != null && !fp.equalsIgnoreCase("false");
        if (absoluteB > 0) {
            m.setAbsoluteMaxReg(absoluteB);
            // Under a constant bound both mechanisms are exactness-preserving by the floor lemma,
            // and they are the point of the reformulation: what can never qualify is pruned at the
            // enumeration and evicted from the tracked set, with nothing about the future assumed.
            m.floorPruneSeeds = true;
            m.evictPermanentlyIrregular = true;
        }
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX;
        m.buffer.fixedMu = MU_PARTITION;             // θ₀ = δ·U(D_old)
        m.buffer.bufferFactorMin = MU_PARTITION;
        m.buffer.bufferFactorMax = MU_PARTITION;
        m.label = (threads == 1) ? "P-RIncHUSP-seq" : "P-RIncHUSP";
        return m;
    }

    /**
     * P-RIncHUSP-P, the fully-incremental variant: every batch Δ_k is mined ONCE at its own natural
     * threshold δ·U(Δ_k) (the same partition lemma, finer partition). Exact after EVERY batch, not just
     * at query time; the price is that k parts mined at the same δ yield ≈k× the candidates of one part.
     * {@link #newProposed} uses the coarsest (2-part) split and is therefore cheaper when the caller only
     * needs the answer at the end.
     */
    public static AlgoPRIncHUSP newProposedPartition(int threads) {
        AlgoPRIncHUSP m = newProposed(threads);
        m.discoverExact = false;
        m.partitionMine = true;
        m.label = "P-RIncHUSP-P";
        return m;
    }

    /**
     * S9: the SAME exact miner with θ₀ moved off its natural value. Exactness must NOT change (the
     * partition lemma does not depend on θ₀); only the seed/discovery cost split does.
     */
    public static AlgoPRIncHUSP newProposedMu(int threads, double mu) {
        AlgoPRIncHUSP m = newProposed(threads);
        m.buffer.fixedMu = mu;
        m.buffer.bufferFactorMin = mu;
        m.buffer.bufferFactorMax = mu;
        m.label = String.format("P-RIncHUSP-mu%.1f", mu);
        return m;
    }

    /**
     * S10: the 2×2 exactness ablation. The two ceilings are INDEPENDENT and need DIFFERENT bounds;
     * this is the table that proves it. Both flags off = the pre-fix algorithm.
     *
     * @param regBound  ρ·N_final seed prune; closes the REGULARITY ceiling (Thm. 2)
     * @param discovery mine ΔD at minUtil−θ₀; closes the UTILITY ceiling (partition lemma)
     */
    public static AlgoPRIncHUSP newProposedAblation(int threads, boolean regBound, boolean discovery, double mu) {
        AlgoPRIncHUSP m = newProposed(threads);
        m.seedPruneByFinalN = regBound;
        m.discoverExact = discovery;
        m.buffer.fixedMu = mu;
        m.buffer.bufferFactorMin = mu;
        m.buffer.bufferFactorMax = mu;
        m.label = "P-RIncHUSP[" + (regBound ? "reg" : "-") + "," + (discovery ? "disc" : "-") + "]";
        return m;
    }

    /**
     * The PRE-FIX algorithm: unsound ρ·N_current seed prune + sub-natural θ₀ (μ=0.4), no discovery.
     * Kept as a baseline because its recall collapse under a tightening ρ (SIGN: 0.9667 at ρ=0.30 →
     * 0.7667 at ρ=0.15) is what forced the exact design, so S7 must show it.
     */
    public static AlgoPRIncHUSP newProposedApprox(int threads) {
        AlgoPRIncHUSP m = newProposedAblation(threads, false, false, muMin);
        m.label = "P-RIncHUSP-approx";
        return m;
    }

    /** S5 ablation: the SAME proposed miner with the OLD per-pattern (inverted-index) maintain;
     *  isolates the content-driven maintain's contribution in the official numbers (identical HS by
     *  construction; the delta is pure maintenance-strategy cost). */
    public static AlgoPRIncHUSP newProposedInvindex(int threads) {
        AlgoPRIncHUSP m = newProposed(threads);
        m.trieMaintain = false;
        m.label = "P-RIncHUSP-invidx";
        return m;
    }

    /** RIncHusp baseline [Ishita2022]: FIXED-μ buffer, sequential, CORRECT utility-list. */
    public static AlgoRIncHUSP newRIncHusp(double mu) {
        AlgoRIncHUSP m = new AlgoRIncHUSP();
        m.bufferFactor = mu;
        if (absoluteB > 0) m.setAbsoluteMaxReg(absoluteB);
        return m;
    }

    /** Naive baseline: re-mine the full DB from scratch each batch, SEQUENTIAL static miner. */
    public static AlgoRemine newRemine() {
        AlgoRemine m = new AlgoRemine();
        if (absoluteB > 0) m.setAbsoluteMaxReg(absoluteB);
        return m;
    }

    /**
     * DECISIVE baseline: the companion study's PARALLEL static RHUSP engine (RDLB scheduler) re-run
     * from scratch on every batch. Answers "a parallel static miner exists, why not just re-run it?".
     * The gap to P-RIncHUSP is precisely the value of parallel INCREMENTAL maintenance.
     */
    public static AlgoParRemine newParRemine(int threads) {
        AlgoParRemine m = new AlgoParRemine();
        m.numThreads = threads;
        // Standard work-stealing over independent prefix branches (STRAT_STEAL), the parallel scheme
        // whose origin is pSPADE (Zaki 2001). This is deliberately the standard primitive, not the
        // companion study's recursive dynamic load balancing: the crossover argument holds for any
        // competent parallel re-miner (re-mining is O(k) whatever the balancing), and a standard,
        // published primitive is citable where an unpublished one is not. Verified to return the same
        // pattern set as the RDLB strategy, so only the timing differs.
        m.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_STEAL;
        m.denseBuffers = true;
        if (absoluteB > 0) m.setAbsoluteMaxReg(absoluteB);
        return m;
    }
}
