package test;

import algorithms.AlgoParRemine;
import algorithms.AlgoRHUSPMinerParallel;

import java.util.List;
import java.util.Map;

/**
 * Applies the declared rule for the utility threshold of a new dataset: the smallest delta of the
 * given ascending grid for which one full parallel re-mine of the whole database finishes within
 * the time limit and returns between {@code lo} and {@code hi} patterns.
 *
 * <pre>
 *   java -cp out test.CalibrateDelta seq eui B limitSeconds lo hi 0.001,0.002,0.005,0.01,0.02,0.05
 * </pre>
 * One line per delta tried (time, pattern count, verdict), then the chosen delta or NONE. Each
 * delta runs in a worker thread; past the limit the JVM exits, so run one dataset per JVM and
 * restart it from the next delta (the printed lines say where it stopped).
 */
public class CalibrateDelta {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        int absB = Integer.parseInt(args[2]);
        long limitMs = Long.parseLong(args[3]) * 1000L;
        int lo = Integer.parseInt(args[4]), hi = Integer.parseInt(args[5]);
        String[] grid = args[6].split(",");
        int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        System.out.printf("%s N=%d B=%d limit=%ds band=[%d,%d] threads=%d%n",
                ExpUtil.datasetTag(seq), all.size(), absB, limitMs / 1000, lo, hi, threads);
        for (String d : grid) {
            double delta = Double.parseDouble(d);
            final int[] count = {-1};
            Thread w = new Thread(() -> {
                AlgoParRemine r = ExpConfig.newParRemine(threads);
                r.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
                r.setAbsoluteMaxReg(absB);
                r.initialBuild(all, delta, 0.0);
                Map<String, long[]> res = r.getHighUtilityPatterns();
                count[0] = res.size();
            });
            w.setDaemon(true);
            long t0 = System.nanoTime();
            w.start();
            w.join(limitMs);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (w.isAlive()) {
                System.out.printf("delta=%s TIMEOUT after %d ms%n", d, ms);
                System.out.println("stopped: restart from the next delta in a fresh JVM");
                System.exit(3);
            }
            boolean ok = count[0] >= lo && count[0] <= hi;
            System.out.printf("delta=%s time=%d ms patterns=%d %s%n", d, ms, count[0], ok ? "ACCEPT" : "reject");
            if (ok) { System.out.println("chosen delta=" + d); return; }
        }
        System.out.println("chosen delta=NONE");
    }
}
