package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.SeqConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine05Bench — does seeding P-RIncHUSP with the companion study's parallel engine (a) preserve the
 * answer and (b) make the incremental miner beat Par-Remine (re-running that same engine every batch)?
 *
 * Correctness gate: the full output tuple (pattern, utility, period) must be IDENTICAL whether D_old is
 * seeded by the in-house VUL/DEUCS enumeration or by the [05] engine — both are exact RHUSP miners at
 * the same threshold, so any difference is a bug.
 *
 * Usage: Engine05Bench <seq> <eui> <delta> <rho> [label] [threads=10]
 */
public class Engine05Bench {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int T = args.length > 5 ? Integer.parseInt(args[5]) : 10;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);

        Res inhouse = measure(() -> prop(false, T), b, d, r);
        Res eng05   = measure(() -> prop(true,  T), b, d, r);
        Res parRe   = measure(() -> ExpConfig.newParRemine(T), b, d, r);

        boolean same = inhouse.tuples.equals(eng05.tuples);

        System.out.printf("%n==== Engine05Bench %s | delta=%.4f | 4-batch A | T=%d ====%n", label, d, T);
        System.out.printf("  P-RIncHUSP  seed=in-house : %6d ms  (HS=%d)%n", inhouse.ms, inhouse.hs);
        System.out.printf("  P-RIncHUSP  seed=[05]     : %6d ms  (HS=%d)   -> %.2fx faster than in-house%n",
                eng05.ms, eng05.hs, inhouse.ms / (double) eng05.ms);
        System.out.printf("  ParRemine   (re-mine x4)  : %6d ms  (HS=%d)%n", parRe.ms, parRe.hs);
        System.out.printf("%n  CORRECTNESS  (pattern,utility,period) in-house == [05]-seeded ? %s%n",
                same ? "YES" : "NO  <-- BUG, STOP");
        System.out.printf("  VERDICT      P-RIncHUSP[05-seeded] vs ParRemine : %.2fx  -> %s%n",
                parRe.ms / (double) eng05.ms,
                eng05.ms < parRe.ms ? "INCREMENTAL WINS" : "re-mine still wins");
    }

    static AlgoPRIncHUSP prop(boolean eng05, int threads) {
        AlgoPRIncHUSP m = ExpConfig.newProposed(threads);
        m.seedWithEngine05 = eng05;
        return m;
    }

    interface Factory { algorithms.IncrementalHUSPMiner get(); }

    /** warm-up + 2 measured (min ms). */
    static Res measure(Factory f, List<List<List<int[]>>> b, double d, double r) {
        ExpUtil.run(f.get(), b, d, r);
        long best = Long.MAX_VALUE; Map<String, String> t = null;
        for (int i = 0; i < 2; i++) {
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(f.get(), b, d, r);
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
