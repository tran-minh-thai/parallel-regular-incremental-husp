package test;

import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DiscoveryBench — does the one-shot reconciliation break the SEED-ONCE CEILING, and at what price?
 *
 * The seed-once ceiling is the only reason P-RIncHUSP is not exact: a pattern below θ₀ in D_old can
 * never be promoted. {@code discoverExact} mines the increments at the SOUND bound θ_disc = minUtil − θ₀
 * (high, hence cheap) and provably recovers every such pattern. The decisive numbers:
 *   recall must reach 1.0000 (matching the full re-mine), and the extra cost must stay well below
 *   the cost of re-mining (ParRemine).
 *
 * Usage: DiscoveryBench <seq> <eui> <delta> <rho> [label] [threads=10]
 */
public class DiscoveryBench {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int T = args.length > 5 ? Integer.parseInt(args[5]) : 10;
        double f = args.length > 6 ? Double.parseDouble(args[6]) : 0.25;   // |D_old| / N_final

        double[] ratios = {f, (1 - f) / 3, (1 - f) / 3, (1 - f) / 3};
        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ratios);
        Set<String> oracle = ExpUtil.oracleCanon(all, d, r);
        System.out.printf("%n### %s  delta=%.4f  rho=%.2f  f=|D_old|/N=%.2f  =>  rho %s f  (%s)%n",
                label, d, r, f, r < f ? "<" : ">=",
                r < f ? "seed reg-prune EFFECTIVE" : "seed reg-prune DEGENERATE");

        Res base   = measure(false, false, T, b, d, r, oracle);
        Res exact  = measure(true,  false, T, b, d, r, oracle);
        Res par    = measureMiner(T, b, d, r, oracle);

        System.out.printf("  oracle (full re-mine) = %d patterns   [T=%d]%n", oracle.size(), T);
        System.out.printf("  %-30s %8s %6s %9s%n", "config", "ms", "HS", "recall");
        row("P-RIncHUSP (approx seed)", base);
        row("P-RIncHUSP-E (exact seed)", exact);
        row("ParRemine (re-mine x4)", par);
        System.out.printf("  -> exact? %s | exact-vs-ParRemine %.2fx | approx-vs-ParRemine %.2fx%n",
                exact.recall >= 0.99995 ? "YES" : "NO ",
                par.ms / (double) exact.ms, par.ms / (double) base.ms);
    }

    static void row(String name, Res x) {
        System.out.printf("  %-30s %6dms %6d %9.4f%n", name, x.ms, x.hs, x.recall);
    }

    static Res measure(boolean finalN, boolean disc, int T,
                       List<List<List<int[]>>> b, double d, double r, Set<String> oracle) {
        AlgoPRIncHUSP warm = cfg(finalN, disc, T);
        ExpUtil.run(warm, b, d, r);
        long best = Long.MAX_VALUE; Res out = new Res();
        for (int i = 0; i < 2; i++) {
            AlgoPRIncHUSP m = cfg(finalN, disc, T);
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(m, b, d, r);
            long ms = System.currentTimeMillis() - t0;
            if (ms < best) { best = ms; out.hs = res.size(); out.recall = ExpUtil.coverage(res, oracle); }
            out.cand = m.discCandidates; out.adm = m.discAccepted;
        }
        out.ms = best; return out;
    }

    static AlgoPRIncHUSP cfg(boolean finalN, boolean disc, int T) {
        AlgoPRIncHUSP m = ExpConfig.newProposed(T);
        m.seedPruneByFinalN = finalN;
        m.discoverExact = disc;
        return m;
    }

    static Res measureMiner(int T, List<List<List<int[]>>> b, double d, double r, Set<String> oracle) {
        ExpUtil.run(ExpConfig.newParRemine(T), b, d, r);            // warm-up
        long best = Long.MAX_VALUE; Res out = new Res();
        for (int i = 0; i < 2; i++) {
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(ExpConfig.newParRemine(T), b, d, r);
            long ms = System.currentTimeMillis() - t0;
            if (ms < best) { best = ms; out.hs = res.size(); out.recall = ExpUtil.coverage(res, oracle); }
        }
        out.ms = best; return out;
    }

    static final class Res { long ms; int hs; double recall; int cand, adm; }
}
