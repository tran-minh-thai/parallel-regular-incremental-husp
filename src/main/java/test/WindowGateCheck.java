package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.HeavyBound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Checks the window gate of bud3rbw ({@link HeavyBound#windowGated}) against {@link HeavyBound#window} on windows whose
 * size n lies around 2B, and on the boundary window n = 2B - 1 built so that form (b) applies at i = B - 1.
 *
 * <p>A violation is a window that has a bound while the gate is closed (the gate would hide a bound the selector of bud3rbx
 * computes). Random windows: B drawn from 1..6, n from 2B - 2 .. 2B + 1, sequences of a few items, so that both closed and
 * open gates occur and form (b) often applies near the boundary. Boundary windows: n = 2B - 1, every sequence light except
 * the one at i = B - 1, which is heavy and holds an item found nowhere else; the bound then exists and must pass the gate.
 *
 * <pre>java -cp out test.WindowGateCheck [trials] [seed]</pre>
 *
 * Prints "window gate windows tested=N violations=V closed=C open-with-bound=O boundary=K boundary-with-form-b=F
 * closed-with-bound=X". X counts closed windows with a bound, which cannot happen (form (a) needs n &lt;= B, form (b) needs n &lt;= 2B - 1). -Dfault=18 closes the
 * gate at n >= 2B - 1, which must violate on the boundary windows. Exit status 1 on any violation or any zero count.
 */
public class WindowGateCheck {
    public static void main(String[] a) {
        int trials = a.length > 0 ? Integer.parseInt(a[0]) : 4000;
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 11;
        AlgoPRIncHUSP.FAULT = Integer.getInteger("fault", 0);
        Random rnd = new Random(seed);
        int tested = 0, bad = 0, closed = 0, openBound = 0, boundary = 0, boundaryB = 0, closedBound = 0;
        for (int t = 0; t < trials; t++) {
            boolean edge = t % 4 == 0;
            int B = 1 + rnd.nextInt(6);
            int n = edge ? 2 * B - 1 : Math.max(1, 2 * B - 2 + rnd.nextInt(4));
            List<List<int[]>> w = new ArrayList<>();
            int heavyAt = B - 1, unique = 1000;
            for (int i = 0; i < n; i++) {
                List<int[]> s = new ArrayList<>();
                int len = 1 + rnd.nextInt(3);
                for (int e = 0; e < len; e++) {
                    int item = rnd.nextInt(4);
                    int u = edge && i != heavyAt ? 1 : 1 + rnd.nextInt(6);
                    s.add(new int[]{item, u});
                }
                if (edge && i == heavyAt) { s.add(new int[]{unique, 5}); s.add(new int[]{rnd.nextInt(4), 3}); }
                w.add(s);
            }
            List<Long> seqUtil = new ArrayList<>();
            Map<Integer, Integer> itemSeqs = new HashMap<>();
            long total = 0, maxU = 0;
            for (List<int[]> s : w) {
                long u = 0; Set<Integer> seen = new HashSet<>();
                for (int[] ev : s) for (int k = 0; k < ev.length; k += 2) { u += ev[k + 1]; seen.add(ev[k]); }
                seqUtil.add(u); total += u; maxU = Math.max(maxU, u);
                for (int x : seen) itemSeqs.merge(x, 1, Integer::sum);
            }
            // edge: theta just above every light sequence (each light sequence has utility <= 3), below the heavy one
            long theta = edge ? 4 : 1 + rnd.nextInt((int) Math.max(1, maxU));
            HeavyBound.Result r = HeavyBound.window(w, seqUtil, itemSeqs, theta, B);
            boolean gated = HeavyBound.windowGated(n, B);
            tested++;
            if (gated) closed++;
            if (r != null && !gated) openBound++;
            if (r != null && n >= 2 * B) closedBound++;
            if (r != null && gated) bad++;
            if (edge) { boundary++; if (r != null && r.form == 'b') boundaryB++; }
        }
        System.out.printf("window gate windows tested=%d violations=%d closed=%d open-with-bound=%d boundary=%d boundary-with-form-b=%d closed-with-bound=%d%n",
                tested, bad, closed, openBound, boundary, boundaryB, closedBound);
        if (bad > 0 || closed == 0 || openBound == 0 || boundaryB == 0 || closedBound > 0) System.exit(1);
    }
}
