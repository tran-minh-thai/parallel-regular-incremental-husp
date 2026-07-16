package test;

import algorithms.AlgoPRIncHUSP;
import java.util.*;

/**
 * Sweeps delta on KOSARAK to find a value that yields a useful number of patterns at an acceptable
 * cost. KOSARAK has its own probe rather than using {@link DeltaProbe} because at 990,002 sequences
 * a badly chosen delta can exhaust memory, so the sweep is bounded and stops early.
 *
 * <p>The pattern count comes from the miner's own result. Because the miner is exact, the set it
 * returns is the same set a full re-mine would produce, so there is no reason to build a
 * ground-truth oracle just to count patterns — and doing so would be far more expensive than the
 * mining itself, since the reference miner is single-threaded and would sweep all 990k sequences
 * again for every delta. The suite skips recall on this dataset for the same reason
 * ({@code numSequences > ExpConfig.coverageMaxN}), so no oracle is needed anywhere.
 *
 * <p>Requirements and behaviour:
 * <ul>
 *   <li>Run with a large heap ({@code -Xmx16g} or more) on a machine with the RAM to back it.
 *       If the heap approaches physical memory the JVM will swap rather than fail, which is far
 *       slower and can fill the disk.</li>
 *   <li>The sweep runs from high delta to low, because a lower delta is always the heavier one.
 *       It stops at the first step that exceeds {@link #PATTERN_CAP}, {@link #PEAK_CAP_MB} or
 *       {@link #TIME_CAP_MS}, or that throws — including {@code OutOfMemoryError}, which is caught
 *       so earlier results still print.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java -Xmx24g -cp out test.KosarakDeltaProbe                        # default sweep
 *   java -Xmx24g -cp out test.KosarakDeltaProbe 0.05 0.03 0.02 0.015   # explicit values
 * </pre>
 */
public class KosarakDeltaProbe {

    /** Above this many patterns delta is too low to be worth the cost. */
    static final int    PATTERN_CAP  = 20000;
    /** Stop before a step that would need more heap than a large machine can give. */
    static final double PEAK_CAP_MB  = 20000;
    /** Stop once a single step takes longer than this. */
    static final long   TIME_CAP_MS  = 300_000;
    /** Regularity threshold held fixed while delta varies. */
    static final double RHO          = 0.30;

    public static void main(String[] args) throws Exception {
        double[] deltas = (args.length > 0)
                ? parse(args)
                : new double[]{0.05, 0.04, 0.03, 0.02, 0.015, 0.01};

        System.out.println("Loading KOSARAK (990,002 sequences)...");
        List<List<int[]>> all = ExpUtil.loadAll("datasets/KOSARAK_seq.txt", "datasets/KOSARAK_eui.txt");
        System.out.printf("Loaded N=%d.  rho=%.2f, 4-batch scenario A, T=10.%n%n", all.size(), RHO);
        System.out.printf("  %8s %10s %10s %10s%n", "delta", "patterns", "time(ms)", "peak(MB)");

        for (double d : deltas) {
            try {
                List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);
                AlgoPRIncHUSP m = ExpConfig.newProposed(10);
                long t0 = System.currentTimeMillis();
                Map<String, long[]> res = ExpUtil.run(m, b, d, RHO);
                long ms = System.currentTimeMillis() - t0;
                int patterns = res.size();
                double peak = m.peakMemoryMB();
                System.out.printf("  %8.4f %10d %10d %10.1f%s%n", d, patterns, ms, peak,
                        patterns >= 200 ? "   <- candidate" : "");
                if (patterns > PATTERN_CAP) {
                    System.out.printf("  stopping: over %d patterns, delta is too low%n", PATTERN_CAP);
                    break;
                }
                if (peak > PEAK_CAP_MB || ms > TIME_CAP_MS) {
                    System.out.println("  stopping: peak memory or time over the cap");
                    break;
                }
            } catch (Throwable t) {
                System.out.printf("  %8.4f  failed (%s) — stopping%n", d, t.getClass().getSimpleName());
                break;
            }
        }
        System.out.println("\nPick the richest delta whose pattern count is useful and whose peak and time"
                + " stay comfortable, then set it in DatasetCatalog.");
    }

    static double[] parse(String[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Double.parseDouble(a[i]);
        return d;
    }
}
