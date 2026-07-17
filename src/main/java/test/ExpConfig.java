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
 * threshold θ_disc = δ·U(ΔD) — each part of the database mined at its own natural threshold, which
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

    /** θ₀ = μ·δ·U(D_old). μ=1 ⟹ θ₀ = δ·U(D_old) and θ_disc = δ·U(ΔD): the partition-lemma value. */
    public static final double MU_PARTITION = 1.0;

    // ===================== μ buffer band — BASELINES ONLY (reference Table 10: μ₀=0.40, [0.40–0.90]) =====================
    public static double muMin = 0.40;          // RIncHusp Fix(0.4) baseline μ
    public static double muMax = 0.90;          // baseline adaptive ceiling
    public static double muFixHigh = 0.90;      // Fix(high μ) baseline — fast but loses patterns

    // ===================== Benchmark =====================
    public static int  warmupRuns   = 1;
    public static int  measuredRuns = 3;
    /** Timeout for one measured run of one algorithm. A run past this is recorded as timed out and
     *  the suite carries on — the safety net that stops a pathological configuration from eating the
     *  night. Keep it tight. */
    public static long runTimeoutMs = 60L * 60 * 1000;   // 60 minutes per run
    /**
     * Timeout for building the recall oracle, kept separate from {@link #runTimeoutMs} on purpose.
     * The oracle is single-threaded by design and re-mines the whole database, so it is far slower
     * than any measured run; sharing one limit would force a choice between giving the oracle room
     * and leaving the benchmark runs unguarded. Raise this one (with {@link #coverageMaxN}) to
     * measure recall on a dataset the oracle cannot reach inside the default hour.
     */
    public static long oracleTimeoutMs = 60L * 60 * 1000;

    // ===================== Thread sweep — PINNED (reproducibility, luuy C7) =====================
    /**
     * Thread counts swept in S1, and the source of the "best T" used by S2/S4/S5/S6/S7/S8.
     * <b>PINNED</b> rather than derived from {@code availableProcessors()}: a machine can silently
     * report fewer cores (macOS Low Power Mode did exactly that mid-study), which would quietly change
     * the tables. Entries above the machine's real core count are DROPPED — never oversubscribed —
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
     * reimplementation — single-threaded on purpose, so that it is independent of the parallel
     * machinery under test — and it re-mines the whole accumulated database at every checkpoint.
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
     * S5 — fine-batch STREAMING regime: D_old = 25% (keeps the seeding threshold sane; a tiny D_old
     * collapses it and OOMs — see DatasetCatalog SIGN note) + 15 equal 5% increments. Content-driven
     * maintenance amortizes shared-prefix matching across every increment, so its edge over a
     * per-pattern inverted index grows with the number of increments; the 4-batch regimes A–D
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
    public static boolean runS5FineBatch   = true;   // fine-batch streaming (isolates the maintain strategy)
    public static boolean runS6DeltaSweep  = true;   // δ-sensitivity sweep (light datasets only)
    public static boolean runS7RhoSweep    = true;   // ρ-sensitivity sweep (light datasets only)
    public static boolean runS8BatchScaling = true;  // batch-count scaling against re-mining
    public static boolean runS9MuSweep     = true;   // buffer factor sweep
    public static boolean runS10Exactness  = true;   // 2x2 ablation of the two seed bounds

    /**
     * Buffer factors swept by S9, where θ₀ = μ·δ·U(D_old). The sweep is what shows μ = 1 to be the
     * cost minimum rather than a tuned constant: recall holds at 1.0 throughout, since exactness does
     * not depend on θ₀, while cost traces a U — the seed shrinks as μ rises and discovery grows to
     * match. 0.4 is the value the sequential baselines use and is included for that comparison.
     */
    public static final double[] S9_MUS = {0.4, 0.7, 1.0, 1.5, 2.0, 3.0};

    // ===================== Sweep ranges for S6/S7/S8 =====================
    /**
     * δ multipliers applied to each dataset's base δ. All are ≥ 1, so minUtil only rises and the
     * pattern space only shrinks: a sweep can never be heavier than the base configuration, which
     * matters because these run unattended.
     */
    public static final double[] S6_DELTA_MULT = {1.0, 1.5, 2.0, 3.0};
    /** ρ multipliers applied to the base ρ, giving 0.15 / 0.30 / 0.45 / 0.60: strict to loose. */
    public static final double[] S7_RHO_MULT = {0.5, 1.0, 1.5, 2.0};
    /**
     * Batch counts for S8, each run as D_old (25%) followed by n−1 increments. The range spans counts
     * small enough for re-mining to still be cheap through counts where it clearly is not, so the two
     * cost curves cross inside the plot: re-mining is linear in the number of updates, incremental
     * maintenance is close to flat.
     */
    public static final int[] S8_BATCH_COUNTS = {2, 4, 8, 16, 32, 64};
    /**
     * Datasets that get the S6–S10 sweeps. The heavy ones are left out: sweeping them would add
     * many hours and the sweeps answer questions the large datasets do not. Not final —
     * {@code --test} swaps in the tiny example datasets so the sweep scenarios can be exercised in
     * seconds before committing to a long run. That check is worth keeping: a scenario that has
     * never run on the small suite has never had its correctness assertions tested at all, and a
     * loose regularity threshold on the real data can easily hide a seeding bug.
     */
    public static java.util.Set<String> s6Datasets =
            new java.util.HashSet<>(java.util.Arrays.asList("SIGN", "LEVIATHAN", "BIBLE"));

    /** {@code --test}: point the sweeps at the tiny example datasets so they run in seconds. */
    public static void enableSweepsForTestSuite() {
        s6Datasets = new java.util.HashSet<>(java.util.Arrays.asList("example", "example2"));
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
     * the first two close independent gaps, so neither alone is enough — S10 ablates them.
     *
     * <ol>
     *   <li>{@code seedPruneByFinalN} — regularity is pruned at ρ·N_final while seeding, the tightest
     *       bound that stays sound. A gap inside D_old survives unchanged when data is appended, and
     *       irregularity is anti-monotone under extension, so pruning at ρ·N_current instead drops
     *       patterns that are irregular within D_old yet regular once the database is complete. The
     *       effect grows as ρ tightens.</li>
     *   <li>{@code discoverExact} — the increment is mined at minUtil − θ₀. Because utility is
     *       additive over a partition, that recovers precisely those patterns too weak in D_old to
     *       have been seeded.</li>
     *   <li>μ = 1 — this sets θ₀ = δ·U(D_old) and the discovery threshold to δ·U(ΔD), so each part is
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
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX;
        m.buffer.fixedMu = MU_PARTITION;             // θ₀ = δ·U(D_old)
        m.buffer.bufferFactorMin = MU_PARTITION;
        m.buffer.bufferFactorMax = MU_PARTITION;
        m.label = (threads == 1) ? "P-RIncHUSP-seq" : "P-RIncHUSP";
        return m;
    }

    /**
     * P-RIncHUSP-P — the fully-incremental variant: every batch Δ_k is mined ONCE at its own natural
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
     * S9 — the SAME exact miner with θ₀ moved off its natural value. Exactness must NOT change (the
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
     * S10 — the 2×2 exactness ablation. The two ceilings are INDEPENDENT and need DIFFERENT bounds;
     * this is the table that proves it. Both flags off = the pre-fix algorithm.
     *
     * @param regBound  ρ·N_final seed prune — closes the REGULARITY ceiling (Thm. 2)
     * @param discovery mine ΔD at minUtil−θ₀ — closes the UTILITY ceiling (partition lemma)
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
     * 0.7667 at ρ=0.15) is what forced the exact design — S7 must show it.
     */
    public static AlgoPRIncHUSP newProposedApprox(int threads) {
        AlgoPRIncHUSP m = newProposedAblation(threads, false, false, muMin);
        m.label = "P-RIncHUSP-approx";
        return m;
    }

    /** S5 ablation: the SAME proposed miner with the OLD per-pattern (inverted-index) maintain —
     *  isolates the content-driven maintain's contribution in the official numbers (identical HS by
     *  construction; the delta is pure maintenance-strategy cost). */
    public static AlgoPRIncHUSP newProposedInvindex(int threads) {
        AlgoPRIncHUSP m = newProposed(threads);
        m.trieMaintain = false;
        m.label = "P-RIncHUSP-invidx";
        return m;
    }

    /** RIncHusp baseline [Ishita2022] — FIXED-μ buffer, sequential, CORRECT utility-list. */
    public static AlgoRIncHUSP newRIncHusp(double mu) {
        AlgoRIncHUSP m = new AlgoRIncHUSP();
        m.bufferFactor = mu;
        return m;
    }

    /** Naive baseline: re-mine the full DB from scratch each batch, SEQUENTIAL static miner. */
    public static AlgoRemine newRemine() { return new AlgoRemine(); }

    /**
     * DECISIVE baseline: the companion study's PARALLEL static RHUSP engine (RDLB scheduler) re-run
     * from scratch on every batch. Answers "a parallel static miner exists — why not just re-run it?".
     * The gap to P-RIncHUSP is precisely the value of parallel INCREMENTAL maintenance.
     */
    public static AlgoParRemine newParRemine(int threads) {
        AlgoParRemine m = new AlgoParRemine();
        m.numThreads = threads;
        m.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;   // the companion paper's best config
        m.denseBuffers = true;
        return m;
    }
}
