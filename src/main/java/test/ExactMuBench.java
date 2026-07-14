package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ExactMuBench — the seed threshold θ₀ is the ONLY free knob of an EXACT incremental miner.
 *
 * <p>P-RIncHUSP-E is exact iff BOTH ceilings are closed:
 * <ul>
 *   <li>regularity: {@code seedPruneByFinalN} (seed prunes at ρ·N_final — sound, Thm. 2), and</li>
 *   <li>utility:    {@code discoverExact}     (mine ΔD at θ_disc = minUtil − θ₀ — sound, Lemma P1).</li>
 * </ul>
 * The two are COUPLED through θ₀ = μ·δ·U(D_old) = (μ·f)·minUtil:
 * raising θ₀ makes the SEED cheaper and the DISCOVERY more expensive. Exactness holds for every θ₀ —
 * only the cost moves. This sweep locates the minimum of seed(θ₀) + discovery(minUtil − θ₀) and asks
 * the only question that matters: does that minimum beat Par-Remine?
 *
 * Usage: ExactMuBench &lt;seq&gt; &lt;eui&gt; &lt;delta&gt; &lt;rho&gt; [label] [threads=10] [f=0.25]
 */
public class ExactMuBench {

    /** μ values; θ₀/minUtil = μ·f. With f=0.25 these are θ₀ ∈ {0.10 … 0.90}·minUtil. */
    static final double[] MUS = {0.4, 1.0, 1.6, 2.0, 2.4, 3.0, 3.6};

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int T = args.length > 5 ? Integer.parseInt(args[5]) : 10;
        double f = args.length > 6 ? Double.parseDouble(args[6]) : 0.25;

        double[] ratios = {f, (1 - f) / 3, (1 - f) / 3, (1 - f) / 3};
        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ratios);
        Set<String> oracle = ExpUtil.oracleCanon(all, d, r);

        long par = parRemine(T, b, d, r, oracle);

        System.out.printf("%n==== ExactMuBench %s | delta=%.4f rho=%.2f | f=%.2f | 4 batches | T=%d ====%n",
                label, d, r, f, T);
        System.out.printf("  oracle = %d patterns   |   Par-Remine (exact, x4) = %d ms%n%n", oracle.size(), par);
        System.out.printf("  %5s %8s %8s %8s %8s %7s %6s %9s %8s  %s%n",
                "mu", "th0/mU", "total", "seed", "maint", "disc", "HS", "recall", "vs.Par", "cand/adm");

        double bestSpeed = 0; double bestMu = 0;
        for (double mu : MUS) {
            R x = measure(mu, T, b, d, r, oracle, f);
            double sp = par / (double) x.total;
            if (x.recall >= 0.99995 && sp > bestSpeed) { bestSpeed = sp; bestMu = mu; }
            System.out.printf("  %5.1f %8.2f %6dms %6dms %6dms %5dms %6d %9.4f %7.2fx  %d/%d%s%n",
                    mu, mu * f, x.total, x.seed, x.maint, x.disc, x.hs, x.recall, sp,
                    x.cand, x.adm, x.recall >= 0.99995 ? "  EXACT" : "");
        }
        System.out.printf("%n  >>> best EXACT config: mu=%.1f  ->  %.2fx vs Par-Remine  %s%n",
                bestMu, bestSpeed,
                bestSpeed > 1.0 ? "*** EXACT AND FASTER ***" : "(still slower — re-mine wins here)");
    }

    static R measure(double mu, int T, List<List<List<int[]>>> b, double d, double r,
                     Set<String> oracle, double f) {
        run(cfg(mu, T), b, d, r);                                   // warm-up
        R best = null;
        for (int i = 0; i < 2; i++) {
            AlgoPRIncHUSP m = cfg(mu, T);
            long[] ph = new long[2];
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(m, b, d, r, ph);   // getHighUtilityPatterns() inside → triggers discovery
            long total = System.currentTimeMillis() - t0;
            if (best == null || total < best.total) {
                best = new R();
                best.total = total; best.seed = ph[0]; best.maint = ph[1]; best.disc = m.discMs;
                best.hs = res.size(); best.recall = ExpUtil.coverage(res, oracle);
                best.cand = m.discCandidates; best.adm = m.discAccepted;
            }
        }
        return best;
    }

    /** Exact configuration: BOTH ceilings closed; μ is the only thing that varies. */
    static AlgoPRIncHUSP cfg(double mu, int T) {
        AlgoPRIncHUSP m = ExpConfig.newProposed(T);
        m.seedPruneByFinalN = true;    // regularity ceiling  (Thm. 2)
        m.discoverExact     = true;    // utility ceiling     (Lemma P1)
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX;
        m.buffer.fixedMu = mu;
        m.buffer.bufferFactorMin = mu;
        m.buffer.bufferFactorMax = Math.max(mu, 0.9);
        return m;
    }

    static void run(AlgoPRIncHUSP m, List<List<List<int[]>>> b, double d, double r) {
        ExpUtil.run(m, b, d, r);
    }

    static long parRemine(int T, List<List<List<int[]>>> b, double d, double r, Set<String> oracle) {
        ExpUtil.run(ExpConfig.newParRemine(T), b, d, r);            // warm-up
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            long t0 = System.currentTimeMillis();
            ExpUtil.run(ExpConfig.newParRemine(T), b, d, r);
            best = Math.min(best, System.currentTimeMillis() - t0);
        }
        return best;
    }

    static final class R { long total, seed, maint, disc; int hs; double recall; int cand, adm; }
}
