package test;

import algorithms.SeqConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * HSCompare — element-level containment of the proposed miner's HS set vs the RIncHusp-Fix(0.4)
 * baseline (and set-level determinism across T). Answers: is proposed ⊇ baseline? equal? do the
 * utilities of shared patterns match (exactness of the measure)? Canonicalizes both key formats via
 * {@link SeqConverter#canonical}. 4-batch A-Uniform (matches official S2). Small datasets only.
 *
 * Usage: HSCompare <seq> <eui> <delta> <rho> [label] [threads=8]
 */
public class HSCompare {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : ExpUtil.datasetTag(seq);
        int threads = args.length > 5 ? Integer.parseInt(args[5]) : 8;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);

        Map<String, Long> P  = canon(ExpUtil.run(ExpConfig.newProposed(threads), b, d, r));  // proposed (default = lazy)
        Map<String, Long> B  = canon(ExpUtil.run(ExpConfig.newRIncHusp(ExpConfig.muMin), b, d, r)); // baseline Fix0.4
        Map<String, Long> P1 = canon(ExpUtil.run(ExpConfig.newProposed(1), b, d, r));         // proposed T=1

        System.out.printf("%n==== HS compare  %s | delta=%.4f rho=%.2f | 4-batch A | proposed T=%d vs RIncHusp-Fix0.4 T=1 ====%n",
                label, d, r, threads);
        System.out.printf("  |proposed|=%d  |baseline|=%d  |proposed(T=1)|=%d%n", P.size(), B.size(), P1.size());

        Set<String> pMinusB = minus(P.keySet(), B.keySet());
        Set<String> bMinusP = minus(B.keySet(), P.keySet());
        System.out.printf("  proposed \\ baseline = %d  (proposed finds, baseline misses)%n", pMinusB.size());
        System.out.printf("  baseline \\ proposed = %d  (baseline finds, PROPOSED MISSES)%n", bMinusP.size());
        System.out.printf("  => proposed superset of baseline (>=)? %s%n", bMinusP.isEmpty() ? "YES" : "NO");
        System.out.printf("  => sets identical (=)?                  %s%n",
                pMinusB.isEmpty() && bMinusP.isEmpty() ? "YES" : "NO");
        if (!bMinusP.isEmpty()) { int c = 0; for (String k : bMinusP) { System.out.println("     baseline-only: " + k); if (++c == 5) break; } }
        if (!pMinusB.isEmpty()) { int c = 0; for (String k : pMinusB) { System.out.println("     proposed-only: " + k); if (++c == 5) break; } }

        int disagree = 0; String ex = null;
        for (String k : P.keySet())
            if (B.containsKey(k) && !P.get(k).equals(B.get(k))) { disagree++; if (ex == null) ex = k + " prop=" + P.get(k) + " base=" + B.get(k); }
        System.out.printf("  utility mismatches on shared patterns = %d%s%n", disagree, ex == null ? "" : ("   e.g. " + ex));

        System.out.printf("  determinism  proposed T=%d vs T=1: set identical? %s%n",
                threads, P.keySet().equals(P1.keySet()) ? "YES" : "NO");
    }

    static Map<String, Long> canon(Map<String, long[]> m) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, long[]> e : m.entrySet()) out.put(SeqConverter.canonical(e.getKey()), e.getValue()[0]);
        return out;
    }

    static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> s = new TreeSet<>(a); s.removeAll(b); return s;
    }
}
