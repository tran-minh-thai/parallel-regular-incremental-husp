package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * AdaptiveProbe (case 1 — the decisive test) — does making μ ACTUALLY vary and gate the buffer beat a
 * fixed μ on the cost × recall trade-off? Fine-grained batches (default 20 × 5%) so the growth signals
 * have range; the "threshold-difficulty" constant r_B is dropped from the per-batch min; and μ(t) gates
 * maintain() by evicting patterns whose utility falls below θ(t)=μ(t)·minUtil.
 *
 * <p>Configs (all on {@link AlgoPRIncHUSP}, inverted-index maintain):
 * <ul>
 *   <li>Fix μ ∈ {0.40, 0.70, 0.90} — seed-once, no eviction (traces the fixed cost/recall curve).</li>
 *   <li>Fix0.40+evict — constant μ but evict below θ (eviction driven only by minUtil growth).</li>
 *   <li>Adapt+evict — μ(t) rises on calm batches (de-saturated r_U/r_G, no r_B) + eviction.</li>
 * </ul>
 * Cost proxy = total matchPatternUtil calls (thread-independent, exact). Recall vs the RHusp oracle.
 *
 * <p>Split = D_old 25% (same seeding regime as the official suite — a 5% D_old collapses the seeding
 * threshold and OOMs, see DatasetCatalog SIGN note) + (nBatches−1) equal fine increments.
 *
 * Usage: {@code AdaptiveProbe <seqFile> <euiFile> <delta> <rho> [label] [nBatches=16] [threads=10]}
 */
public class AdaptiveProbe {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : seq;
        int nB = args.length > 5 ? Integer.parseInt(args[5]) : 16;
        int threads = args.length > 6 ? Integer.parseInt(args[6]) : 10;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        double[] ratios = new double[nB];
        ratios[0] = 0.25;                                   // D_old — keeps the seeding threshold sane
        Arrays.fill(ratios, 1, nB, 0.75 / (nB - 1));        // fine increments
        List<List<List<int[]>>> batches = ExpUtil.split(all, ratios);
        int totalN = all.size();
        System.out.printf("%n==== %s | N=%d | delta=%.4f rho=%.2f | D_old=25%% + %d x %.2f%% increments | T=%d ====%n",
                label, totalN, d, r, nB - 1, 75.0 / (nB - 1), threads);

        Set<String> oracle = ExpUtil.oracleCanon(all, d, r);
        System.out.printf("oracle(RHusp) = %d patterns%n%n", oracle.size());

        System.out.printf("%-16s %7s %6s %8s %14s %8s  %-22s %s%n",
                "config", "recall", "HS", "peakSHS", "matchCalls", "time", "mu-trajectory", "extra");
        System.out.println("---------------------------------------------------------------------------------------------------------------");
        AlgoPRIncHUSP.COUNT = true;
        Set<String> ref =
        cfg("Fix0.40",        batches, d, r, totalN, threads, oracle, null, AdaptiveBuffer.Strategy.FIX,      0.40, false, false, false, false, false);
        cfg("Fix0.90",        batches, d, r, totalN, threads, oracle, null, AdaptiveBuffer.Strategy.FIX,      0.90, false, false, false, false, false);
        cfg("Fix0.40+evict",  batches, d, r, totalN, threads, oracle, ref,  AdaptiveBuffer.Strategy.FIX,      0.40, false, true,  false, false, false);
        cfg("Adapt+evict",    batches, d, r, totalN, threads, oracle, ref,  AdaptiveBuffer.Strategy.COMBINED, 0.40, true,  true,  false, false, false);
        cfg("Lazy-Fix0.90",   batches, d, r, totalN, threads, oracle, ref,  AdaptiveBuffer.Strategy.FIX,      0.90, false, false, false, false, true);
        cfg("Lazy-Adapt",     batches, d, r, totalN, threads, oracle, ref,  AdaptiveBuffer.Strategy.COMBINED, 0.40, true,  false, false, false, true);
        cfg("Lazy-Max(1.0)",  batches, d, r, totalN, threads, oracle, ref,  AdaptiveBuffer.Strategy.FIX,      1.00, false, false, false, false, true);
    }

    /** Runs one config; returns its final HS key set. {@code refHS != null} => print HS==ref check
     *  (the lazy rows must be IDENTICAL to Fix0.40 by construction — the dormancy bound is sound). */
    static Set<String> cfg(String name, List<List<List<int[]>>> b, double d, double r, int totalN, int threads,
                    Set<String> oracle, Set<String> refHS, AdaptiveBuffer.Strategy strat, double fixMu,
                    boolean deSat, boolean evict, boolean trend, boolean reseed, boolean lazy) {
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = threads;
        m.useInvertedIndex = true;
        m.evict = evict;
        m.trendSpare = trend;
        m.reseed = reseed;
        m.lazy = lazy;
        m.buffer.strategy = strat;
        m.buffer.fixedMu = fixMu;
        m.buffer.bufferFactorMin = ExpConfig.muMin;
        m.buffer.bufferFactorMax = ExpConfig.muMax;
        if (deSat) {                                     // de-saturate the batch-varying signals; drop constant r_B
            m.buffer.includeBaseSignal = false;
            m.buffer.uLower = 0.05; m.buffer.uUpper = 1.0;
            m.buffer.gLower = 0.05; m.buffer.gUpper = 1.0;
        }
        int peakSHS = 0;
        StringBuilder traj = new StringBuilder();
        long t0 = System.currentTimeMillis();
        m.hintTotalSequences(totalN);
        m.initialBuild(b.get(0), d, r);
        peakSHS = Math.max(peakSHS, m.bufferedCount());
        traj.append(String.format("%.2f", m.buffer.lastBufferFactor));
        for (int i = 1; i < b.size(); i++) {
            m.processBatch(b.get(i));
            peakSHS = Math.max(peakSHS, m.bufferedCount());
            if (i % 4 == 0) traj.append(",").append(String.format("%.2f", m.buffer.lastBufferFactor));
        }
        long ms = System.currentTimeMillis() - t0;
        double recall = ExpUtil.coverage(m.getHighUtilityPatterns(), oracle);
        Set<String> hs = new TreeSet<>(m.getHighUtilityPatterns().keySet());
        StringBuilder extra = new StringBuilder();
        if (reseed) extra.append(String.format("cand=%d new=%d resur=%d abort=%d ",
                m.discCandidates, m.discAccepted, m.discResurrected, m.discAborted));
        if (lazy) extra.append(String.format("wake=%d t1Skip=%d t2Skip=%d ",
                m.lazyWakeups, m.lazyBoundSkips, m.lazyT2Skips));
        if (refHS != null) extra.append(hs.equals(refHS) ? "HS==Fix0.40" : "HS!=Fix0.40(" + symDiff(hs, refHS) + ")");
        System.out.printf("%-16s %7.4f %6d %8d %,14d %6dms  [%-20s] %s%n",
                name, recall, m.getHighUtilityPatterns().size(), peakSHS, m.matchCalls.sum(), ms, traj, extra);
        m.close();
        return hs;
    }

    /** |A △ B| = |A ∪ B| − |A ∩ B|. */
    static int symDiff(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a); u.addAll(b);
        Set<String> i = new TreeSet<>(a); i.retainAll(b);
        return u.size() - i.size();
    }
}
