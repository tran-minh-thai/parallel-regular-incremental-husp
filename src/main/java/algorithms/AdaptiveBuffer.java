package algorithms;

import java.util.List;

/**
 * Buffer threshold θ = μ × minUtil, holding patterns whose utility falls in {@code [θ, minUtil)}.
 * <p>
 * The proposed miner fixes μ = 1 ({@code FIX}), the value the partition lemma identifies as the one
 * that makes the incremental result exact. The four adaptive strategies below instead choose μ per
 * batch from three risk signals (batch utility ratio, growth rate, threshold difficulty), lowering μ
 * when risk is high to favour coverage. The official experiment suite (ExpConfig) always runs
 * {@code FIX}; the adaptive modes are exercised only by the diagnostic probes (see {@code MuProbe}),
 * which use them to show how μ trades coverage for cost.
 */
public class AdaptiveBuffer {

    /**
     * How μ is chosen each batch. {@code FIX} is a constant threshold; the adaptive modes differ in
     * which signals they use — COMBINED takes the min of all three, GROWTH/MINBASE/UTILRATIO use one
     * each (r_G / r_θ / r_U).
     */
    public enum Strategy { COMBINED, GROWTH, MINBASE, UTILRATIO, FIX }

    /** Active strategy. */
    public Strategy strategy = Strategy.COMBINED;
    /** Fixed μ value when {@code strategy == FIX} (e.g. 0.4 = SC5, 0.9 = SC6). */
    public double fixedMu = 0.4;

    /**
     * COMBINED only: include the "threshold difficulty" signal r_B = δ in the per-batch min().
     * r_B is CONSTANT across batches (it is a per-dataset value), so with the reference bounds it
     * saturates factorBase to μ_min and pins μ. Set {@code false} to drive per-batch adaptation from
     * the genuinely batch-varying signals (r_U growth, r_G growth) only; r_B then just sets the band.
     */
    public boolean includeBaseSignal = true;

    // bufferFactorMin is the initial μ of P-RIncHUSP (on the first batch, with no risk signal,
    // computeBufferFactor returns bufferFactorMin). For a fair comparison this value must equal
    // AlgoRIncHUSP.bufferFactor (both 0.4): the two algorithms initialize with the same buffer
    // threshold θ₀ = μ·minUtil; only on later batches does P-RIncHUSP lower μ adaptively (that is the
    // measured contribution).
    public double bufferFactorMin = 0.4;
    public double bufferFactorMax = 0.9;

    /** [lower, upper] bounds to normalize each risk signal to [0,1]. */
    public double uLower = 0.01, uUpper = 0.20;   // batch utility ratio
    public double bLower = 0.001, bUpper = 0.005; // threshold difficulty (minUtilRatio)
    public double gLower = 0.05, gUpper = 0.25;   // growth rate

    public double lastBufferFactor = 0.4;

    /**
     * Compute {@code μ} for the current batch from three risk signals:
     * @param batchUtil   total utility of batch ΔD
     * @param prevTotalUD total DB utility before the batch (for r_u)
     * @param batchSize   number of sequences in the batch
     * @param prevN       number of sequences before the batch (for r_g)
     * @param minUtilRatio δ (threshold difficulty r_b)
     */
    public double computeBufferFactor(long batchUtil, long prevTotalUD, int batchSize,
                            int prevN, double minUtilRatio) {
        // FIX: fixed threshold, independent of batch signals (RIncHusp Fix(μ) baseline)
        if (strategy == Strategy.FIX) { lastBufferFactor = fixedMu; return fixedMu; }
        if (prevTotalUD == 0 || prevN == 0) { lastBufferFactor = bufferFactorMin; return bufferFactorMin; }
        double range = bufferFactorMax - bufferFactorMin;

        double rU = (double) batchUtil / prevTotalUD;        // utility ratio
        double rB = minUtilRatio;                            // threshold difficulty
        double rG = (double) batchSize / prevN;              // growth rate

        double factorUtil   = bufferFactorMax - risk(rU, uLower, uUpper) * range;
        double factorBase   = bufferFactorMax - risk(rB, bLower, bUpper) * range;
        double factorGrowth = bufferFactorMax - risk(rG, gLower, gUpper) * range;

        // select μ by strategy; high risk -> low μ to favor coverage
        double bufferFactor;
        switch (strategy) {
            case GROWTH:    bufferFactor = factorGrowth; break;
            case MINBASE:   bufferFactor = factorBase;   break;
            case UTILRATIO: bufferFactor = factorUtil;   break;
            case COMBINED:
            default:
                bufferFactor = Math.min(factorUtil, factorGrowth);
                if (includeBaseSignal) bufferFactor = Math.min(bufferFactor, factorBase);
        }
        lastBufferFactor = Math.max(bufferFactorMin, Math.min(bufferFactorMax, bufferFactor));
        return lastBufferFactor;
    }

    /** f(x) = x^{0.25} normalized over [lower, upper]. */
    private static double risk(double value, double lower, double upper) {
        if (value <= lower) return 0.0;
        if (value >= upper) return 1.0;
        return Math.pow((value - lower) / (upper - lower), 0.25);
    }

    /** Utility: total utility of a batch. */
    public static long batchUtility(List<List<int[]>> batch) {
        long total = 0;
        for (List<int[]> seq : batch)
            for (int[] ev : seq)
                for (int k = 1; k < ev.length; k += 2) total += ev[k];
        return total;
    }
}
