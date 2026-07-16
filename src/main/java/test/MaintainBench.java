package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;
import algorithms.SeqConverter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * MaintainBench — does the content-driven trie maintain (parallel over sequences, shared-prefix work)
 * beat the per-pattern re-match AND the sequential RIncHusp-Fix0.4 baseline? 4-batch A-Uniform (= S2).
 * Each config: 1 warm-up + 2 measured (min). Correctness gate: trie HS set == invindex HS set.
 *
 * Usage: MaintainBench <seq> <eui> <delta> <rho> [label] [threads=10]
 */
public class MaintainBench {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int hi = args.length > 5 ? Integer.parseInt(args[5]) : 10;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);
        int N = all.size();
        System.out.printf("%n==== MaintainBench %s | N=%d | delta=%.4f rho=%.2f | 4-batch A ====%n", label, N, d, r);

        R invHi  = measure(() -> proposed(false, hi), b, d, r);
        R trieHi = measure(() -> proposed(true,  hi), b, d, r);
        R inv1   = measure(() -> proposed(false, 1),  b, d, r);
        R trie1  = measure(() -> proposed(true,  1),  b, d, r);
        R base   = measure(() -> ExpConfig.newRIncHusp(ExpConfig.muMin), b, d, r);

        System.out.printf("  %-22s %7s  %6s  %s%n", "config", "ms", "HS", "HS==invindex?");
        row("invindex  T=" + hi, invHi, invHi.hs);
        row("trie      T=" + hi, trieHi, invHi.hs);
        row("invindex  T=1",     inv1, invHi.hs);
        row("trie      T=1",     trie1, invHi.hs);
        row("baseline  Fix0.4",  base, invHi.hs);

        System.out.printf("%n  VERDICT (LEVIATHAN target = beat baseline %d ms):%n", base.ms);
        System.out.printf("    trie T=%d vs baseline : %.2fx  -> %s%n", hi, (double) base.ms / trieHi.ms,
                trieHi.ms < base.ms ? "FASTER (goal met)" : "still slower");
        System.out.printf("    trie T=1  vs baseline : %.2fx  (sequential-algorithm quality)%n", (double) base.ms / trie1.ms);
        System.out.printf("    trie vs invindex T=%d : %.2fx faster%n", hi, (double) invHi.ms / trieHi.ms);
    }

    static AlgoPRIncHUSP proposed(boolean trie, int th) {
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = th; m.useInvertedIndex = true; m.trieMaintain = trie;
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX; m.buffer.fixedMu = ExpConfig.muMin;
        m.buffer.bufferFactorMin = ExpConfig.muMin; m.buffer.bufferFactorMax = ExpConfig.muMax;
        return m;
    }

    interface Factory { algorithms.IncrementalHUSPMiner get(); }

    /** 1 warm-up + 2 measured (min ms); returns canonical HS set from the last run. */
    static R measure(Factory f, List<List<List<int[]>>> b, double d, double r) {
        run(f.get(), b, d, r);                                  // warm-up
        long best = Long.MAX_VALUE; Set<String> hs = null;
        for (int i = 0; i < 2; i++) {
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = run(f.get(), b, d, r);
            long ms = System.currentTimeMillis() - t0;
            if (ms < best) best = ms;
            hs = canon(res);
        }
        R out = new R(); out.ms = best; out.set = hs; out.hs = hs.size(); return out;
    }

    static Map<String, long[]> run(algorithms.IncrementalHUSPMiner m, List<List<List<int[]>>> b, double d, double r) {
        return ExpUtil.run(m, b, d, r);
    }

    static Set<String> canon(Map<String, long[]> m) {
        Set<String> s = new TreeSet<>();
        for (String k : m.keySet()) s.add(SeqConverter.canonical(k));
        return s;
    }

    static R REF;
    static void row(String name, R x, int refHs) {
        if (REF == null) REF = x;                              // first row (invindex T=hi) is the reference
        String chk = x.set.equals(REF.set) ? "YES" : "NO (symDiff=" + symDiff(x.set, REF.set) + ")";
        System.out.printf("  %-22s %7d  %6d  %s%n", name, x.ms, x.hs, chk);
    }

    static int symDiff(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a); u.addAll(b);
        Set<String> i = new TreeSet<>(a); i.retainAll(b);
        return u.size() - i.size();
    }

    static final class R { long ms; int hs; Set<String> set; }
}
