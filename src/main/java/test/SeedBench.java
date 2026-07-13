package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.SeqConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SeedBench — does fork-join work-stealing on the D_old seeding enumeration raise parallel speedup
 * without changing the answer? Compares forkSeed off vs on at T threads, plus the T=1 sequential
 * baseline, on 4-batch A-Uniform. Correctness gate: fork-on output (pattern,utility,period) identical
 * to fork-off. Reports speedup S(T)=T1/TT for both.
 *
 * Usage: SeedBench <seq> <eui> <delta> <rho> [label] [threads=10]
 */
public class SeedBench {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int T = args.length > 5 ? Integer.parseInt(args[5]) : 10;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);

        Res off1 = measure(false, 1, b, d, r);
        Res offT = measure(false, T, b, d, r);
        Res onT  = measure(true,  T, b, d, r);
        Res on1  = measure(true,  1, b, d, r);   // fork disabled internally when T=1; sanity same as off1

        boolean same = off1.tuples.equals(onT.tuples) && off1.tuples.equals(offT.tuples);

        System.out.printf("%n==== SeedBench %s | delta=%.4f | 4-batch A | T=%d ====%n", label, d, T);
        System.out.printf("  fork OFF  T=1  : %6d ms  (HS=%d)%n", off1.ms, off1.hs);
        System.out.printf("  fork OFF  T=%-2d : %6d ms  speedup=%.2fx%n", T, offT.ms, off1.ms / (double) offT.ms);
        System.out.printf("  fork ON   T=%-2d : %6d ms  speedup=%.2fx%n", T, onT.ms, off1.ms / (double) onT.ms);
        System.out.printf("  fork ON   T=1  : %6d ms  (grain-fallback sanity)%n", on1.ms);
        System.out.printf("  => fork gain vs OFF @T=%d : %.2fx | HS (pattern,util,period) identical? %s%n",
                T, offT.ms / (double) onT.ms, same ? "YES" : "NO  <-- STOP");
    }

    static AlgoPRIncHUSP cfg(boolean fork, int threads) {
        AlgoPRIncHUSP m = ExpConfig.newProposed(threads);   // trie maintain, seed at mu_min
        m.forkSeed = fork;
        return m;
    }

    /** warm-up + 2 measured (min ms); returns canonical (pattern -> "util|period") map + HS count. */
    static Res measure(boolean fork, int threads, List<List<List<int[]>>> b, double d, double r) {
        ExpUtil.run(cfg(fork, threads), b, d, r);           // warm-up
        long best = Long.MAX_VALUE; Map<String, String> t = null;
        for (int i = 0; i < 2; i++) {
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(cfg(fork, threads), b, d, r);
            long ms = System.currentTimeMillis() - t0;
            if (ms < best) best = ms;
            t = canon(res);
        }
        Res out = new Res(); out.ms = best; out.tuples = t; out.hs = t.size(); return out;
    }

    static Map<String, String> canon(Map<String, long[]> res) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : res.entrySet())
            out.put(SeqConverter.canonical(e.getKey()), e.getValue()[0] + "|" + e.getValue()[1]);
        return out;
    }

    static final class Res { long ms; int hs; Map<String, String> tuples; }
}
