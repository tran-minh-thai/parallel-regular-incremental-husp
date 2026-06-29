package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoRIncHUSP;

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

    // ===================== Scenario on/off switches =====================
    public static boolean runS1Scalability = true;   // P-RIncHUSP sweep over thread count
    public static boolean runS2Compare     = true;   // compare against Adaptive-seq + RIncHusp Fix
    public static boolean runS4Distribution = true;  // 4 distributions A/B/C/D

    // ===================== Miner factory =====================
    /** P-RIncHUSP (proposed) — COMBINED adaptive buffer, runs {@code threads} threads. */
    public static AlgoPRIncHUSP newProposed(int threads) {
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = threads;
        m.buffer.strategy = AdaptiveBuffer.Strategy.COMBINED;
        m.buffer.bufferFactorMin = muMin;
        m.buffer.bufferFactorMax = muMax;
        m.label = (threads == 1) ? "Adaptive-seq" : "P-RIncHUSP";
        return m;
    }

    /** RIncHusp baseline [Ishita2022] — FIXED-μ buffer, sequential, CORRECT utility-list. */
    public static AlgoRIncHUSP newRIncHusp(double mu) {
        AlgoRIncHUSP m = new AlgoRIncHUSP();
        m.bufferFactor = mu;
        return m;
    }
}
