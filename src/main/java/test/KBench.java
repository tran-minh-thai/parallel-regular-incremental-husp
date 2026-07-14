package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KBench — the batch-count crossover, the structural reason incremental must win.
 *
 * <p>Par-Remine pays k full re-mines of a growing DB: cost grows super-linearly in k.
 * P-RIncHUSP-E pays one seed + Σ maintains + one discovery. Total maintain work is
 * |pats| × |ΔD| — every new sequence is matched against the tracked set exactly once,
 * <b>independent of how that ΔD was chopped into batches</b>. So our cost is ≈flat in k
 * while the baseline's is linear. k = 2 is the baseline's best case; k = 4 (what we have
 * been measuring all along) is still nearly its best case.
 *
 * Usage: KBench &lt;seq&gt; &lt;eui&gt; &lt;delta&gt; &lt;rho&gt; [label] [threads=10] [f=0.25]
 */
public class KBench {

    static final int[] KS = {2, 4, 8, 16, 32};
    static final double MU = 1.0;      // θ₀ = δ·U(D_old) — the partition-lemma value (parameter-free)

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int T = args.length > 5 ? Integer.parseInt(args[5]) : 10;
        double f = args.length > 6 ? Double.parseDouble(args[6]) : 0.25;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        Set<String> oracle = ExpUtil.oracleCanon(all, d, r);

        System.out.printf("%n==== KBench %s | delta=%.4f rho=%.2f | f=%.2f | mu=%.1f (exact) | T=%d ====%n",
                label, d, r, f, MU, T);
        System.out.printf("  oracle = %d patterns%n%n", oracle.size());
        System.out.println("  E = 2-part  (mine ΔD once, exact AT QUERY)   P = k-part (mine each Δ_k once, exact EVERY BATCH)");
        System.out.printf("%n  %3s | %8s %7s %9s | %8s %7s %9s | %10s | %7s %7s%n",
                "k", "E total", "E disc", "E recall", "P total", "P disc", "P recall",
                "Par-Remine", "E vs P", "P vs Par");

        for (int k : KS) {
            List<List<List<int[]>>> b = ExpUtil.split(all, ratios(k, f));
            R e = measure(false, T, b, d, r, oracle);   // 2-part: discoverExact
            R p = measure(true,  T, b, d, r, oracle);   // k-part: partitionMine
            long par = parRemine(T, b, d, r);

            System.out.printf("  %3d | %6dms %5dms %9.4f | %6dms %5dms %9.4f | %8dms | %6.2fx %6.2fx%s%n",
                    k, e.ms, e.disc, e.rec, p.ms, p.disc, p.rec, par,
                    par / (double) e.ms, par / (double) p.ms,
                    (e.rec >= 0.99995 && p.rec >= 0.99995) ? "  both EXACT" : "  *** NOT EXACT ***");
        }
    }

    static R measure(boolean partition, int T, List<List<List<int[]>>> b,
                     double d, double r, Set<String> oracle) {
        ExpUtil.run(cfg(partition, T), b, d, r);                       // warm-up
        R best = null;
        long[] ph = new long[2];
        for (int i = 0; i < 2; i++) {
            AlgoPRIncHUSP m = cfg(partition, T);
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(m, b, d, r, ph);
            long ms = System.currentTimeMillis() - t0;
            if (best == null || ms < best.ms) {
                best = new R();
                best.ms = ms; best.seed = ph[0]; best.maint = ph[1]; best.disc = m.discMs;
                best.hs = res.size(); best.rec = ExpUtil.coverage(res, oracle);
            }
        }
        return best;
    }

    static long parRemine(int T, List<List<List<int[]>>> b, double d, double r) {
        ExpUtil.run(ExpConfig.newParRemine(T), b, d, r);                // warm-up
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            long t0 = System.currentTimeMillis();
            ExpUtil.run(ExpConfig.newParRemine(T), b, d, r);
            best = Math.min(best, System.currentTimeMillis() - t0);
        }
        return best;
    }

    static final class R { long ms, seed, maint, disc; int hs; double rec; }

    /** D_old = f, then (k−1) equal increments over the remaining (1−f). */
    static double[] ratios(int k, double f) {
        double[] out = new double[k];
        out[0] = f;
        for (int i = 1; i < k; i++) out[i] = (1 - f) / (k - 1);
        return out;
    }

    static AlgoPRIncHUSP cfg(boolean partition, int T) {
        AlgoPRIncHUSP m = ExpConfig.newProposed(T);
        m.seedPruneByFinalN = true;              // regularity ceiling closed (Thm. 2)
        m.discoverExact     = !partition;        // utility ceiling closed — coarse 2-part split
        m.partitionMine     = partition;         //                       — fine k-part split
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX;
        m.buffer.fixedMu = MU;
        m.buffer.bufferFactorMin = MU;
        m.buffer.bufferFactorMax = MU;
        return m;
    }
}
