package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoRHUSPMinerParallel;
import algorithms.HeavyBound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Checks {@link HeavyBound} two ways, on random small inputs with repeated items.
 *
 * <ol>
 *   <li>One sequence: the number of distinct patterns whose utility in the sequence (maximum over embeddings, counted by
 *       brute force over every subset of occurrences) reaches theta is never below 2^log2Product.</li>
 *   <li>One window: the discovery engine, configured as a discovery attempt configures it (suffix window, floor prune, bound
 *       B, threshold theta), outputs at least 2^bound patterns whenever {@link HeavyBound#window} returns a bound. B is kept
 *       small so that both forms occur.</li>
 * </ol>
 *
 * <pre>java -cp out test.HeavyBoundCheck [trials] [seed]</pre>
 *
 * Part 1 draws the alphabet size from 1..4 so that items repeat often. Prints "heavy bound sequences tested=N violations=V repeated=R" and "heavy bound windows tested=N violations=V form-a=A
 * form-b=B". -Dfault=15 makes the bound 2^d (repeats ignored), which must violate part 1.
 */
public class HeavyBoundCheck {
    public static void main(String[] a) {
        int trials = a.length > 0 ? Integer.parseInt(a[0]) : 3000;
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 7;
        AlgoPRIncHUSP.FAULT = Integer.getInteger("fault", 0);
        Random rnd = new Random(seed);
        int tested = 0, bad = 0, repeated = 0;
        for (int t = 0; t < trials; t++) {
            List<int[]> s = randomSequence(rnd, 1 + rnd.nextInt(4));
            int occ = 0; Set<Integer> items = new HashSet<>();
            long total = 0;
            for (int[] ev : s) for (int k = 0; k < ev.length; k += 2) { occ++; items.add(ev[k]); total += ev[k + 1]; }
            if (occ > 11) continue;
            long theta = 1 + rnd.nextInt((int) total);
            long exact = exactCount(s, theta);
            double lb = HeavyBound.log2Product(s, total - theta, -1, -1);
            tested++;
            if (items.size() < occ) repeated++;
            if (Math.pow(2, lb) > exact + 1e-9) bad++;
        }
        System.out.printf("heavy bound sequences tested=%d violations=%d repeated=%d%n", tested, bad, repeated);

        int wTested = 0, wBad = 0, formA = 0, formB = 0;
        for (int t = 0; t < trials / 10; t++) {
            int n = 1 + rnd.nextInt(6);
            List<List<int[]>> w = new ArrayList<>();
            for (int i = 0; i < n; i++) w.add(randomSequence(rnd, 8));
            List<Long> util = new ArrayList<>();
            Map<Integer, Integer> itemSeqs = new HashMap<>();
            long maxU = 0;
            for (List<int[]> s : w) {
                long u = 0; Set<Integer> seen = new HashSet<>();
                for (int[] ev : s) for (int k = 0; k < ev.length; k += 2) { u += ev[k + 1]; seen.add(ev[k]); }
                util.add(u); maxU = Math.max(maxU, u);
                for (int x : seen) itemSeqs.merge(x, 1, Integer::sum);
            }
            long theta = 1 + rnd.nextInt((int) Math.max(1, maxU));
            int bound = 1 + rnd.nextInt(n + 1);
            HeavyBound.Result r = HeavyBound.window(w, util, itemSeqs, theta, bound);
            if (r == null) continue;
            AlgoRHUSPMinerParallel e = SelectorCalibration.engine(1);
            e.forcedMinUtil = theta;
            e.forcedMaxReg = bound;
            e.runAlgorithmInMemory(w, 1e-12, 1.0);
            wTested++;
            if (r.form == 'a') formA++; else formB++;
            if (Math.pow(2, r.log2) > e.finalPatterns.size() + 1e-9) wBad++;
        }
        System.out.printf("heavy bound windows tested=%d violations=%d form-a=%d form-b=%d%n", wTested, wBad, formA, formB);
        System.exit(tested == 0 || wTested == 0 || bad > 0 || wBad > 0 ? 1 : 0);
    }

    /** Up to 5 events of up to 3 distinct items from an alphabet of `alphabet`, utilities 1..9. */
    static List<int[]> randomSequence(Random rnd, int alphabet) {
        List<int[]> s = new ArrayList<>();
        int events = 1 + rnd.nextInt(5);
        for (int e = 0; e < events; e++) {
            int m = 1 + rnd.nextInt(Math.min(3, alphabet));
            List<Integer> pool = new ArrayList<>();
            for (int x = 1; x <= alphabet; x++) pool.add(x);
            java.util.Collections.shuffle(pool, rnd);
            List<Integer> chosen = new ArrayList<>(pool.subList(0, m));
            java.util.Collections.sort(chosen);
            int[] ev = new int[2 * m];
            for (int i = 0; i < m; i++) { ev[2 * i] = chosen.get(i); ev[2 * i + 1] = 1 + rnd.nextInt(9); }
            s.add(ev);
        }
        return s;
    }

    /** Distinct patterns (lists of sorted itemsets) whose best embedding into s reaches theta, by brute force. */
    static long exactCount(List<int[]> s, long theta) {
        List<int[]> occ = new ArrayList<>();                 // {event, item, utility}
        for (int e = 0; e < s.size(); e++) for (int k = 0; k < s.get(e).length; k += 2) occ.add(new int[]{e, s.get(e)[k], s.get(e)[k + 1]});
        Map<String, Long> best = new HashMap<>();
        for (int mask = 1; mask < (1 << occ.size()); mask++) {
            StringBuilder key = new StringBuilder();
            long u = 0; int lastEvent = -1;
            for (int i = 0; i < occ.size(); i++) {
                if ((mask >> i & 1) == 0) continue;
                int[] o = occ.get(i);
                if (o[0] != lastEvent) { key.append(lastEvent < 0 ? "(" : ")("); lastEvent = o[0]; } else key.append(' ');
                key.append(o[1]);
                u += o[2];
            }
            key.append(')');
            best.merge(key.toString(), u, Math::max);
        }
        long n = 0;
        for (long u : best.values()) if (u >= theta) n++;
        return n;
    }
}
