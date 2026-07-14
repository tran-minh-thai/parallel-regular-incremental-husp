package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoParRemine;
import algorithms.AlgoRHUSPMinerParallel;
import algorithms.AlgoRIncHUSP;
import algorithms.AlgoRemine;

/**
 * CENTRALIZED configuration for the PARALLEL experiment — every constant lives in ONE place for
 * CONSISTENT, REPRODUCIBLE evaluation. See {@link ExperimentOfficial} for the S1–S4 design.
 * <p>
 * <b>μ is no longer a tuning knob.</b> The RIncHusp line treats μ as a "semi-high buffer" factor to be
 * chosen or adapted in [0.4, 0.9]. The partition lemma settles it: with {@link #MU_PARTITION}=1 the seed
 * threshold is θ₀ = δ·U(D_old) and the discovery threshold is θ_disc = δ·U(ΔD) — <i>each part mined at
 * its own natural threshold</i>, which is exactly the condition under which the union of the per-part
 * mines is COMPLETE. Every μ &lt; 1 lowers θ₀ below that natural threshold, exploding the seed while
 * buying no recall that discovery does not already guarantee; every μ &gt; 1 raises θ₀ and pushes the
 * same work into a more expensive discovery. μ = 1 is both the sound choice and the measured cost
 * minimum. {@link #muMin}/{@link #muMax} survive only to configure the RIncHusp <i>baselines</i>.
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
    public static long runTimeoutMs = 60L * 60 * 1000;   // 60 minutes per run

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
    /** Measure recall ONLY WHEN numSequences ≤ threshold (in-memory RHusp oracle feasible). */
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
     * collapses it and OOMs — see DatasetCatalog SIGN note) + 15 equal 5% increments. The lazy
     * buffer's savings scale with #increments × dormancy, so the 4-batch regimes A–D undersell it;
     * this is also the realistic incremental-mining scenario (many small arrivals on a large base).
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
    public static boolean runS2Compare     = true;   // compare against Lazy-seq + RIncHusp Fix
    public static boolean runS4Distribution = true;  // 4 distributions A/B/C/D
    public static boolean runS5FineBatch   = true;   // fine-batch streaming (isolates the maintain strategy)
    public static boolean runS6DeltaSweep  = true;   // δ-sensitivity sweep (light datasets only)
    public static boolean runS7RhoSweep    = true;   // ρ-sensitivity sweep (light datasets only)
    public static boolean runS8BatchScaling = true;  // batch-count CROSSOVER vs Par-Remine (light datasets)
    public static boolean runS9MuSweep     = true;   // θ₀ sweep: recall≡1 ∀μ, cost is U-shaped, min at μ=1
    public static boolean runS10Exactness  = true;   // 2x2 ablation: which flag closes which ceiling

    /**
     * S9 — θ₀ = μ·δ·U(D_old). Sweeping μ is how we SHOW that μ = 1 is not a tuned constant:
     * recall stays 1.0000 at EVERY μ (exactness is invariant to θ₀ — the partition lemma), while the cost
     * is U-shaped (seed falls, discovery rises) with its minimum exactly at μ = 1. μ = 0.4 is the RIncHusp
     * "semi-high buffer" value we inherited and is included to show what it cost us (SIGN: 5.6× slower).
     */
    public static final double[] S9_MUS = {0.4, 0.7, 1.0, 1.5, 2.0, 3.0};

    // ===================== S6/S7/S8 sensitivity & scaling (light datasets only) =====================
    /** δ multipliers × each swept dataset's BASE δ. All >= 1.0 (monotone up) so minUtil only RISES →
     *  fewer patterns, less memory: safe, no OOM risk on the long M5 run. */
    public static final double[] S6_DELTA_MULT = {1.0, 1.5, 2.0, 3.0};
    /** ρ multipliers × base ρ (0.30 → 0.15/0.30/0.45/0.60): stricter→looser regularity. */
    public static final double[] S7_RHO_MULT = {0.5, 1.0, 1.5, 2.0};
    /**
     * Batch counts for S8 (D_old 25% + (n−1) increments). Spans SMALL counts — where re-mining with the
     * parallel engine is still cheap — through LARGE ones, so the CROSSOVER between the two cost curves
     * is visible: re-mining is O(#updates), incremental maintenance is flat.
     */
    public static final int[] S8_BATCH_COUNTS = {2, 4, 8, 16, 32, 64};
    /**
     * Datasets that get the sweeps S6/S7/S8/S9/S10 — the FAST ones. FIFA/KOSARAK excluded (a sweep would
     * add 20h+). NOT final: {@code --test} adds the tiny example datasets so the sweep scenarios are
     * actually EXERCISED before a 3–5 h M5 run commits to them. The last time a scenario shipped without
     * running on the tiny suite, an item-filter bug (trailing-gap prune at seeding) survived into the
     * real benchmarks, where loose maxReg masked it.
     */
    public static java.util.Set<String> s6Datasets =
            new java.util.HashSet<>(java.util.Arrays.asList("SIGN", "LEVIATHAN", "BIBLE"));

    /** {@code --test}: run every sweep on the tiny example datasets too (seconds, not hours). */
    public static void enableSweepsForTestSuite() {
        s6Datasets = new java.util.HashSet<>(java.util.Arrays.asList("example", "example2"));
    }

    /**
     * Datasets that run S5 DESPITE {@code s1Only}. SIGN is s1Only because S4's B-Increasing split
     * (10% D_old) collapses the seeding threshold and OOMs — S5 seeds from 25% D_old, so that risk
     * does not apply. SIGN is also the dataset where laziness demonstrably pays (dense: ~46k tracked
     * candidates, −26% match calls in the 16-batch probe); LEVIATHAN/BIBLE measured ≈0 gain, so
     * without SIGN the S5 table would show none of the mechanism's upside.
     */
    public static final java.util.Set<String> s5ExtraDatasets = java.util.Collections.singleton("SIGN");

    // ===================== Miner factory =====================
    /**
     * P-RIncHUSP (proposed), runs {@code threads} threads. <b>EXACT</b>: recall = 1.0 against the full
     * re-mine oracle, by construction and measured on every dataset. Three ingredients:
     *
     * <ol>
     *   <li><b>{@code seedPruneByFinalN}</b> — the seed prunes regularity at ρ·N_final, the tightest
     *       SOUND bound (an inner gap inside D_old survives verbatim into D, and is anti-monotone under
     *       extension). The old default ρ·N_current is unsound: it silently dropped patterns that are
     *       irregular inside D_old but regular in the final DB. That, not the utility buffer, is what
     *       cost SIGN its 30th pattern — and it got far worse as ρ tightened (recall 0.77 at ρ=0.15).</li>
     *   <li><b>{@code discoverExact}</b> — mines ΔD at θ_disc = minUtil − θ₀, which by additivity
     *       (Lemma P1) catches every pattern too weak in D_old to be seeded.</li>
     *   <li><b>μ = 1</b> — <i>not</i> a tuned constant. It makes θ₀ = δ·U(D_old) and θ_disc = δ·U(ΔD):
     *       each part is mined at its OWN natural threshold. The partition lemma then guarantees
     *       completeness, and μ = 1 is also the empirical cost minimum on every dataset (seed cost falls
     *       with θ₀, discovery cost rises; the U-shape bottoms out exactly here). The sub-1 "semi-high
     *       buffer" of the RIncHusp line drags θ₀ to 0.1·minUtil — a 10× lower threshold that explodes
     *       the seed (SIGN: 5283 ms vs 463 ms) and buys nothing discovery does not already guarantee.</li>
     * </ol>
     *
     * Maintenance is the CONTENT-DRIVEN parallel trie ({@code trieMaintain}): a shared prefix is matched
     * once for all patterns extending it, work split over disjoint ascending sequence ranges. Output is
     * verified element-wise identical to the per-pattern re-match (TrieVerify) and deterministic across T.
     * D_old is seeded by the companion study's parallel engine [05].
     */
    public static AlgoPRIncHUSP newProposed(int threads) {
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = threads;
        m.seedWithEngine05 = true;                   // D_old seeded by the companion study's parallel engine
        m.trieMaintain = true;                       // content-driven parallel maintain (THIS paper's contribution)
        m.seedPruneByFinalN = true;                  // SOUND regularity bound at seeding (ρ·N_final)
        m.discoverExact = true;                      // SOUND utility recovery from ΔD  -> together: EXACT
        m.forkSeed = false;                          // in-house enumeration unused when seeding with [05]
        m.lazy = false;
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX;
        m.buffer.fixedMu = MU_PARTITION;             // θ₀ = δ·U(D_old): each part at its natural threshold
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
