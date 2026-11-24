package test;

import algorithms.AlgoParRemine;
import algorithms.AlgoRHUSPMinerParallel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Wall time of one full parallel re-mine of a whole database, the quantity that assigns a dataset to the cheap or the
 * expensive regime of the schedule comparison.
 *
 *   java -cp out test.RemineTimer <seq> <eui> <B> <delta> <runs>     (threads from -Dthreads, default all cores)
 *
 * The re-mine is the one the delta rule calibrates (test.CalibrateDelta): AlgoParRemine with the RDLB strategy at the
 * absolute regularity bound B. One unmeasured warm-up run, then the measured runs; prints one line per run, the pattern
 * count (which must be equal in every run) and the median.
 */
public class RemineTimer {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        int absB = Integer.parseInt(args[2]);
        double delta = Double.parseDouble(args[3]);
        int runs = Integer.parseInt(args[4]);
        int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        System.out.printf("%s N=%d B=%d delta=%s threads=%d runs=%d%n", ExpUtil.datasetTag(seq), all.size(), absB, args[3], threads, runs);
        List<Long> ms = new ArrayList<>();
        int count = -1;
        for (int r = -1; r < runs; r++) {
            System.gc();
            long t0 = System.nanoTime();
            AlgoParRemine m = ExpConfig.newParRemine(threads);
            m.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
            m.setAbsoluteMaxReg(absB);
            m.initialBuild(all, delta, 0.0);
            Map<String, long[]> res = m.getHighUtilityPatterns();
            long t = (System.nanoTime() - t0) / 1_000_000;
            m.close();
            if (count >= 0 && res.size() != count) throw new IllegalStateException("pattern count changed: " + count + " -> " + res.size());
            count = res.size();
            if (r < 0) { System.out.printf("warm-up ms=%d patterns=%d%n", t, count); continue; }
            ms.add(t);
            System.out.printf("run %d ms=%d patterns=%d%n", r + 1, t, count);
        }
        List<Long> s = new ArrayList<>(ms);
        Collections.sort(s);
        long med = s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2;
        System.out.printf("median ms=%d patterns=%d%n", med, count);
    }
}
