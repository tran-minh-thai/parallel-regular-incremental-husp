package algorithms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * A lower bound on the number of patterns a discovery over a window must output, from its heavy sequences.
 *
 * <p>A window sequence s is heavy when u(s) &gt;= theta, the discovery threshold. Give each item x of s a count r_x of its
 * cheapest occurrences, with the sum of those utilities at most u(s) - theta. Removing any c_x &lt;= r_x of them leaves an
 * embedding of utility &gt;= theta, and different count vectors leave different item multisets, so s alone yields
 * prod_x (r_x + 1) distinct patterns of utility &gt;= theta in the window (utilities are non-negative and a pattern's
 * utility in a sequence is the maximum over its embeddings). Whether the discovery must output them depends on the
 * regularity test of a suffix window: every gap, including the leading gap (local index + 1) and the trailing gap
 * (window size - local index), must be at most the bound B.
 *
 * <ul>
 *   <li>Form (a): when the window has at most B sequences no gap can exceed B, so the bound is the largest product over
 *       the heavy sequences.</li>
 *   <li>Form (b): for a heavy sequence at local index i with i + 1 &lt;= B and n - i &lt;= B that holds an item found in no
 *       other window sequence, keep one occurrence of that item (its costliest, first in sequence order on ties). Every
 *       such pattern occurs in the window only in s, so it passes the test; patterns of different sequences differ, so
 *       the products add up.</li>
 * </ul>
 *
 * <p>Any feasible count vector gives a valid bound; r is chosen greedily by the marginal gain log2((r+2)/(r+1)) per unit
 * of utility, ties broken by the smaller item. Fault 15 replaces the product by 2^d, d being the number of occurrences
 * removable cheapest first with repeats ignored: wrong whenever an item repeats.
 */
public final class HeavyBound {
    private HeavyBound() {}

    /** Result of a window bound: log2 of the bound and its form ('a' or 'b'). */
    public static final class Result {
        public final double log2;
        public final char form;
        Result(double log2, char form) { this.log2 = log2; this.form = form; }
    }

    /**
     * The bound over a window, or null when neither form applies.
     *
     * @param window   the window's sequences, in order (events of item/utility pairs)
     * @param seqUtil  utility of each window sequence, same order
     * @param itemSeqs for each item, the number of window sequences that contain it
     * @param theta    discovery threshold, at least 1
     * @param bound    regularity bound B of the discovery
     */
    public static Result window(List<List<int[]>> window, List<Long> seqUtil, Map<Integer, Integer> itemSeqs,
                                long theta, int bound) {
        if (theta < 1) return null;
        final int n = window.size();
        double formA = Double.NEGATIVE_INFINITY, formB = Double.NEGATIVE_INFINITY;
        boolean anyB = false;
        for (int i = 0; i < n; i++) {
            long u = seqUtil.get(i);
            if (u < theta) continue;
            List<int[]> s = window.get(i);
            if (n <= bound) formA = Math.max(formA, log2Product(s, u - theta, -1, -1));
            if (i + 1 <= bound && (long) n - i <= bound) {
                int keep = -1, keepU = -1;
                for (int[] ev : s)
                    for (int k = 0; k < ev.length; k += 2)
                        if (itemSeqs.getOrDefault(ev[k], 0) == 1 && ev[k + 1] > keepU) { keep = ev[k]; keepU = ev[k + 1]; }
                if (keep >= 0) {
                    double l = log2Product(s, u - theta, keep, keepU);
                    formB = anyB ? log2Sum(formB, l) : l;
                    anyB = true;
                }
            }
        }
        boolean a = formA != Double.NEGATIVE_INFINITY;
        if (!a && !anyB) return null;
        if (a && (!anyB || formA >= formB)) return new Result(formA, 'a');
        return new Result(formB, 'b');
    }

    /** log2(2^x + 2^y). */
    static double log2Sum(double x, double y) {
        double hi = Math.max(x, y), lo = Math.min(x, y);
        return hi + Math.log1p(Math.pow(2, lo - hi)) / Math.log(2);
    }

    /**
     * log2 of prod_x (r_x + 1) for the greedy count vector within slack; one occurrence of keepItem with utility keepU is
     * never removed (keepItem = -1: none).
     */
    public static double log2Product(List<int[]> s, long slack, int keepItem, int keepU) {
        TreeMap<Integer, List<Integer>> costs = new TreeMap<>();
        boolean kept = keepItem < 0;
        for (int[] ev : s)
            for (int k = 0; k < ev.length; k += 2) {
                if (!kept && ev[k] == keepItem && ev[k + 1] == keepU) { kept = true; continue; }
                costs.computeIfAbsent(ev[k], z -> new ArrayList<>()).add(ev[k + 1]);
            }
        if (AlgoPRIncHUSP.FAULT == 15) {                       // FAULT 15: 2^d, repeats ignored
            List<Integer> all = new ArrayList<>();
            for (List<Integer> c : costs.values()) all.addAll(c);
            Collections.sort(all);
            long used = 0; int d = 0;
            for (int c : all) { if (used + c > slack) break; used += c; d++; }
            return d;
        }
        for (List<Integer> c : costs.values()) Collections.sort(c);
        // [ratio, item, index of the next occurrence to remove]; larger ratio first, then smaller item
        PriorityQueue<double[]> pq = new PriorityQueue<>((p, q) -> p[0] != q[0] ? Double.compare(q[0], p[0]) : Double.compare(p[1], q[1]));
        for (Map.Entry<Integer, List<Integer>> e : costs.entrySet())
            pq.add(new double[]{ratio(0, e.getValue().get(0)), e.getKey(), 0});
        long used = 0;
        double log2 = 0;
        while (!pq.isEmpty()) {
            double[] t = pq.poll();
            int x = (int) t[1], j = (int) t[2];
            List<Integer> c = costs.get(x);
            if (used + c.get(j) > slack) continue;              // x takes no more; a cheaper occurrence of another item may fit
            used += c.get(j);
            log2 += Math.log((j + 2.0) / (j + 1.0)) / Math.log(2);
            if (j + 1 < c.size()) pq.add(new double[]{ratio(j + 1, c.get(j + 1)), x, j + 1});
        }
        return log2;
    }

    private static double ratio(int j, int cost) {
        return Math.log((j + 2.0) / (j + 1.0)) / Math.max(1e-9, cost);
    }
}
