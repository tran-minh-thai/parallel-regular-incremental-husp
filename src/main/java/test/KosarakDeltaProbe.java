package test;

import algorithms.AlgoPRIncHUSP;
import java.util.*;

/**
 * KOSARAK delta probe — pick a delta giving a meaningful pattern count on the 990K-sequence set.
 *
 * FAST version (2026-07-15): reports the pattern count from the EXACT miner itself
 * ({@code res.size()} — which equals the oracle size because the miner is exact, recall 1.0),
 * and does NOT build the ground-truth oracle. Building the oracle (a single-threaded static
 * re-mine over 990,002 sequences via {@code oracleCanon}) cost ~1 HOUR per delta and was NOT
 * even reflected in the printed time — a 5-hour sweep that looked like "a few seconds" per row.
 * We only need the pattern count to choose delta; the run() time here is the true per-step cost.
 * (KOSARAK's recall is SKIPPED in the actual suite too — N > coverageMaxN — so no oracle is
 *  ever needed for this dataset.)
 *
 * SAFETY — RUN ONLY ON THE 32GB M5 MACHINE. Do NOT run on an 8GB box (heap >> RAM → swap thrash
 * → full disk). Sweep is DESCENDING and STOPS at the first sign of trouble (a lower delta is only
 * heavier): patterns over PATTERN_CAP, peak over PEAK_CAP_MB, per-step time over TIME_CAP_MS, or
 * any Throwable (e.g. OutOfMemoryError caught defensively).
 *
 * Run:
 *   java -Xmx24g -cp out test.KosarakDeltaProbe
 *   java -Xmx24g -cp out test.KosarakDeltaProbe 0.05 0.03 0.02 0.015 0.01   # custom sweep
 */
public class KosarakDeltaProbe {

    static final int    PATTERN_CAP  = 20000;    // too many patterns ⇒ delta too low; stop
    static final double PEAK_CAP_MB  = 20000;    // 20 GB — leave headroom on a 32 GB machine
    static final long   TIME_CAP_MS  = 300_000;  // 5 min / step
    static final double RHO          = 0.30;

    public static void main(String[] args) throws Exception {
        double[] deltas = (args.length > 0)
                ? parse(args)
                : new double[]{0.05, 0.04, 0.03, 0.02, 0.015, 0.01};

        System.out.println("Loading KOSARAK (990,002 sequences) — this needs the 32GB machine ...");
        List<List<int[]>> all = ExpUtil.loadAll("datasets/KOSARAK_seq.txt", "datasets/KOSARAK_eui.txt");
        System.out.printf("Loaded N=%d.  rho=%.2f, 4-batch scenario A, T=10.  (no oracle — count from the exact miner)%n%n",
                all.size(), RHO);
        System.out.printf("  %8s %10s %10s %10s%n", "delta", "patterns", "time(ms)", "peak(MB)");

        for (double d : deltas) {
            try {
                List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);
                AlgoPRIncHUSP m = ExpConfig.newProposed(10);
                long t0 = System.currentTimeMillis();
                Map<String, long[]> res = ExpUtil.run(m, b, d, RHO);   // exact ⇒ res.size() = #HS = oracle size
                long ms = System.currentTimeMillis() - t0;
                int patterns = res.size();
                double peak = m.peakMemoryMB();
                System.out.printf("  %8.4f %10d %10d %10.1f%s%n", d, patterns, ms, peak,
                        patterns >= 200 ? "   <- candidate" : "");
                if (patterns > PATTERN_CAP) {
                    System.out.printf("  *** patterns over cap (%d) — STOP, delta too low ***%n", PATTERN_CAP);
                    break;
                }
                if (peak > PEAK_CAP_MB || ms > TIME_CAP_MS) {
                    System.out.printf("  *** peak/time over cap — STOP before going lower ***%n");
                    break;
                }
            } catch (Throwable t) {   // OOM / anything: a lower delta is only worse
                System.out.printf("  %8.4f  *** %s — STOP ***%n", d, t.getClass().getSimpleName());
                break;
            }
        }
        System.out.println("\nPick the richest delta whose pattern count is meaningful and whose peak/time stay"
                + " comfortable — that becomes KOSARAK's delta for the S1 re-run.");
    }

    static double[] parse(String[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Double.parseDouble(a[i]);
        return d;
    }
}
