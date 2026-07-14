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
 * <b>Shared & fair μ:</b> the proposed method (P-RIncHUSP) uses an ADAPTIVE buffer with FLOOR
 * {@link #muMin}=0.4 (equal to the μ of the RIncHusp Fix(0.4) baseline); μ self-adjusts only on
 * later batches within [{@link #muMin}, {@link #muMax}] — that is the measured contribution.
 */
public final class ExpConfig {
    private ExpConfig() {}

    // ===================== μ buffer band (reference Table 10: μ₀=0.40, range [0.40–0.90]) =====================
    public static double muMin = 0.40;          // adaptive FLOOR = baseline Fix(0.4) μ
    public static double muMax = 0.90;          // adaptive CEILING
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
    public static boolean runS8BatchScaling = true;  // bounded memory over #update cycles (light datasets)

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
    /** Datasets that get S6/S7/S8 — the FAST ones. FIFA/KOSARAK excluded (a sweep would add 20h+). */
    public static final java.util.Set<String> s6Datasets =
            new java.util.HashSet<>(java.util.Arrays.asList("SIGN", "LEVIATHAN", "BIBLE"));

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
     * P-RIncHUSP (proposed), runs {@code threads} threads. Seeds the RIncHusp buffer at μ_min·minUtil
     * (coverage identical to Fix(μ_min)) and maintains it via the CONTENT-DRIVEN parallel trie
     * ({@code trieMaintain}): a shared prefix is matched once for all patterns extending it, work split
     * over disjoint ascending sequence ranges. Output (pattern,utility,period) is verified element-wise
     * identical to the per-pattern re-match and to the sequential baseline (TrieVerify), deterministic
     * across T; measured faster than RIncHusp-Fix0.4 on ALL datasets (1.6–5.3× @ T=10, MaintainBench).
     * Lazy buffering is OFF: it showed no runtime gain on the M5 suite and is incompatible with the trie.
     * The seeding enumeration (94–97% of runtime) uses fork-join work-stealing ({@code forkSeed}),
     * which raised speedup from 1.45× to 4.27× on LEVIATHAN with identical output (SeedBench).
     */
    public static AlgoPRIncHUSP newProposed(int threads) {
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = threads;
        m.seedWithEngine05 = true;                   // D_old seeded by the companion study's parallel engine
        m.trieMaintain = true;                       // content-driven parallel maintain (THIS paper's contribution)
        m.forkSeed = false;                          // in-house enumeration unused when seeding with [05]
        m.lazy = false;
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX;
        m.buffer.fixedMu = muMin;                    // seed AND maintain buffer at μ_min·minUtil (= Fix0.4 coverage)
        m.buffer.bufferFactorMin = muMin;
        m.buffer.bufferFactorMax = muMax;
        m.label = (threads == 1) ? "P-RIncHUSP-seq" : "P-RIncHUSP";
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
