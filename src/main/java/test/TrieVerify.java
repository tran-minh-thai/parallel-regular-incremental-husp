package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;
import algorithms.SeqConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * TrieVerify — rigorous correctness gate for the content-driven maintain BEFORE it becomes the suite
 * default: verifies the FULL output tuple (pattern, utility, period) is identical between trie and
 * inverted-index maintain, and that trie is deterministic across T (T vs 1). Not a timing probe.
 *
 * Usage: TrieVerify <seq> <eui> <delta> <rho> [label] [threads=8]
 */
public class TrieVerify {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int th = args.length > 5 ? Integer.parseInt(args[5]) : 8;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);

        Map<String, String> inv   = canon(ExpUtil.run(cfg(false, 1),  b, d, r));   // invindex T=1  (reference)
        Map<String, String> trieT = canon(ExpUtil.run(cfg(true,  th), b, d, r));   // trie     T=th
        Map<String, String> trie1 = canon(ExpUtil.run(cfg(true,  1),  b, d, r));   // trie     T=1

        System.out.printf("%n==== TrieVerify %s | delta=%.4f | 4-batch A ====%n", label, d);
        System.out.printf("  |invindex|=%d  |trie T=%d|=%d  |trie T=1|=%d%n", inv.size(), th, trieT.size(), trie1.size());
        int m1 = fullDiff(inv, trieT), m2 = fullDiff(trieT, trie1);
        System.out.printf("  trie T=%d vs invindex : (pattern,utility,period) mismatches = %d  -> %s%n",
                th, m1, m1 == 0 ? "IDENTICAL" : "DIFFERS");
        System.out.printf("  trie T=%d vs trie T=1 : determinism mismatches           = %d  -> %s%n",
                th, m2, m2 == 0 ? "IDENTICAL" : "DIFFERS");
        System.out.println(m1 == 0 && m2 == 0 ? "  VERIFY OK" : "  VERIFY FAILED");
    }

    static AlgoPRIncHUSP cfg(boolean trie, int th) {
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = th; m.lazy = false; m.useInvertedIndex = true; m.trieMaintain = trie;
        m.buffer.strategy = AdaptiveBuffer.Strategy.FIX; m.buffer.fixedMu = ExpConfig.muMin;
        m.buffer.bufferFactorMin = ExpConfig.muMin; m.buffer.bufferFactorMax = ExpConfig.muMax;
        return m;
    }

    /** canonical pattern -> "utility|period" string (full tuple). */
    static Map<String, String> canon(Map<String, long[]> res) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : res.entrySet())
            out.put(SeqConverter.canonical(e.getKey()), e.getValue()[0] + "|" + e.getValue()[1]);
        return out;
    }

    /** Count keys whose full tuple differs or is missing between a and b (symmetric). */
    static int fullDiff(Map<String, String> a, Map<String, String> b) {
        int n = 0;
        for (String k : new TreeSet<>(a.keySet())) if (!a.get(k).equals(b.get(k))) n++;
        for (String k : b.keySet()) if (!a.containsKey(k)) n++;
        return n;
    }
}
