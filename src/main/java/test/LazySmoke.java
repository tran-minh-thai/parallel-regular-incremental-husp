package test;

import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * LazySmoke — pre-flight check before the official suite runs with the lazy buffer.
 * Runs {@link ExpConfig#newProposed} (EXACTLY what the suite instantiates, lazy included) and the
 * same miner with {@code lazy=false}, on one dataset, 4 batches A-Uniform. Verifies the HS sets are
 * IDENTICAL, prints time / peak memory / lazy stats / bookkeeping footprint. Sized for KOSARAK
 * (990k sequences, needs -Xmx16g): confirms the per-batch index stays small and the checkpoint cap
 * never needs to fire there.
 *
 * Usage: {@code LazySmoke <seqFile> <euiFile> <delta> <rho> [threads=10]}
 */
public class LazySmoke {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        int threads = args.length > 4 ? Integer.parseInt(args[4]) : 10;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);
        System.out.printf("%n==== LazySmoke %s | N=%d | delta=%.4f rho=%.2f | 4 batches A | T=%d ====%n",
                ExpUtil.datasetTag(seq), all.size(), d, r, threads);

        Res lazyOn = run(true, b, d, r, all.size(), threads);
        Res lazyOff = run(false, b, d, r, all.size(), threads);

        boolean same = lazyOn.hsKeys.equals(lazyOff.hsKeys);
        System.out.printf("%n%-9s %8s %10s %8s %8s%n", "mode", "time", "peakMB", "HS", "SHS");
        System.out.printf("%-9s %6dms %9.1fMB %8d %8d   wake=%d t1Skip=%d t2Skip=%d%n",
                "lazy ON", lazyOn.ms, lazyOn.peakMb, lazyOn.hs, lazyOn.shs,
                lazyOn.wake, lazyOn.t1, lazyOn.t2);
        System.out.printf("%-9s %6dms %9.1fMB %8d %8d%n",
                "lazy OFF", lazyOff.ms, lazyOff.peakMb, lazyOff.hs, lazyOff.shs);
        System.out.println("footprint: " + lazyOn.footprint);
        System.out.printf("HS sets identical: %s%n", same ? "YES" : "NO  <-- MUST BE YES, DO NOT LAUNCH SUITE");
        if (!same) System.exit(1);
        System.out.println("SMOKE OK");
    }

    static Res run(boolean lazyOn, List<List<List<int[]>>> b, double d, double r, int totalN, int threads) {
        AlgoPRIncHUSP m = ExpConfig.newProposed(threads);   // the suite's exact factory (lazy=true inside)
        if (!lazyOn) m.lazy = false;                        // OFF variant = same config minus laziness
        long t0 = System.currentTimeMillis();
        Map<String, long[]> res = ExpUtil.run(m, b, d, r);  // includes hint + initialBuild + batches + close
        Res out = new Res();
        out.ms = System.currentTimeMillis() - t0;
        out.peakMb = m.peakMemoryMB();
        out.hs = res.size();
        out.shs = m.bufferedCount();
        out.hsKeys = new TreeSet<>(res.keySet());
        out.wake = m.lazyWakeups; out.t1 = m.lazyBoundSkips; out.t2 = m.lazyT2Skips;
        out.footprint = m.lazyFootprint();
        return out;
    }

    static final class Res {
        long ms; double peakMb; int hs, shs;
        long wake, t1, t2;
        Set<String> hsKeys;
        String footprint;
    }
}
