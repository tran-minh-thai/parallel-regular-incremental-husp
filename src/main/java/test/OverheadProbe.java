package test;

import algorithms.AdaptiveBuffer;
import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoRIncHUSP;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * OverheadProbe — does the maintain() inverted-index fix reverse the RIncHusp-Fix0.4 speed anomaly?
 * Same warmed JVM, 4-batch A-Uniform. Measures, for the PROPOSED miner ({@link AlgoPRIncHUSP}, COMBINED):
 * <ul>
 *   <li><b>OLD</b> ({@code useInvertedIndex=false}) — original |pats|×|Δseq| cross-product.</li>
 *   <li><b>NEW</b> ({@code useInvertedIndex=true})  — re-match only sequences containing every pattern item.</li>
 * </ul>
 * and the <b>baseline</b> {@link AlgoRIncHUSP} Fix(0.4). Each timed value is 1 warm-up + 1 measured run
 * with COUNT off (no instrumentation overhead); a separate COUNT-on run supplies exact call/miss stats.
 *
 * Usage: {@code OverheadProbe <seqFile> <euiFile> <delta> <rho> [label] [threads=1]}
 */
public class OverheadProbe {

    static int THREADS = 1;

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double d = Double.parseDouble(args[2]), r = Double.parseDouble(args[3]);
        String label = args.length > 4 ? args[4] : seq;
        THREADS = args.length > 5 ? Integer.parseInt(args[5]) : 1;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ExpConfig.SCEN_A);
        int totalN = all.size();
        int[] sizes = new int[batches.size()];
        for (int i = 0; i < batches.size(); i++) sizes[i] = batches.get(i).size();
        System.out.printf("%n==== %s | N=%d | delta=%.4f rho=%.2f | 4 batches A-Uniform %s | proposed T=%d, baseline T=1 (warmed) ====%n",
                label, totalN, d, r, java.util.Arrays.toString(sizes), THREADS);

        Res oldR = measure(false, batches, d, r, totalN);
        Res newR = measure(true,  batches, d, r, totalN);
        long[] base = measureBaseline(batches, d, r, totalN);   // {ms, hs, shs}

        Set<String> a = new TreeSet<>(oldR.hsKeys), b = new TreeSet<>(newR.hsKeys);
        System.out.printf("%n-- Correctness (proposed COMBINED; mu/batch=%s) --%n", newR.mus);
        System.out.printf("   proposed OLD : HS=%-5d SHS=%d%n", oldR.hs, oldR.shs);
        System.out.printf("   proposed NEW : HS=%-5d SHS=%d   (HS set == OLD? %s, symDiff=%d | SHS==? %s)%n",
                newR.hs, newR.shs, a.equals(b) ? "YES" : "NO", symDiff(a, b), oldR.shs == newR.shs ? "YES" : "NO");
        System.out.printf("   baseline Fix : HS=%-5d SHS=%d%n", base[1], base[2]);

        System.out.printf("%n-- Timing (COUNT off, warmed) --%n");
        System.out.printf("   proposed OLD (T=%d) : total=%6dms  build=%5dms  incr=%6dms%n", THREADS, oldR.ms, oldR.buildMs, oldR.incrMs);
        System.out.printf("   proposed NEW (T=%d) : total=%6dms  build=%5dms  incr=%6dms   (incr %.0f%% faster, total %.0f%% faster vs OLD)%n",
                THREADS, newR.ms, newR.buildMs, newR.incrMs,
                100.0 * (oldR.incrMs - newR.incrMs) / Math.max(1, oldR.incrMs),
                100.0 * (oldR.ms - newR.ms) / Math.max(1, oldR.ms));
        System.out.printf("   baseline Fix0.4 (T=1): total=%6dms%n", base[0]);
        System.out.printf("   => VERDICT: proposed NEW / baseline = %.2fx  (build alone / baseline = %.2fx)%n",
                (double) newR.ms / Math.max(1, base[0]), (double) newR.buildMs / Math.max(1, base[0]));

        System.out.printf("%n-- maintain() match calls (COUNT on) --%n");
        System.out.printf("   OLD : calls=%,d  misses=%,d (%.1f%%)%n", oldR.calls, oldR.miss, pct(oldR.miss, oldR.calls));
        System.out.printf("   NEW : calls=%,d  misses=%,d (%.1f%%)   (%.0f%% fewer calls)%n",
                newR.calls, newR.miss, pct(newR.miss, newR.calls),
                100.0 * (oldR.calls - newR.calls) / Math.max(1, oldR.calls));
    }

    static Res measure(boolean useInv, List<List<List<int[]>>> b, double d, double r, int totalN) {
        run(useInv, false, b, d, r, totalN);                 // warm-up (discard)
        Res timed = run(useInv, false, b, d, r, totalN);     // clean timing
        Res counted = run(useInv, true, b, d, r, totalN);    // exact call/miss stats
        timed.calls = counted.calls;
        timed.miss = counted.miss;
        return timed;
    }

    static Res run(boolean useInv, boolean count, List<List<List<int[]>>> b, double d, double r, int totalN) {
        AlgoPRIncHUSP.COUNT = count;
        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = THREADS;
        m.useInvertedIndex = useInv;
        m.buffer.strategy = AdaptiveBuffer.Strategy.COMBINED;
        m.buffer.bufferFactorMin = ExpConfig.muMin;
        m.buffer.bufferFactorMax = ExpConfig.muMax;
        List<String> mus = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        m.hintTotalSequences(totalN);
        m.initialBuild(b.get(0), d, r);
        long tBuild = System.currentTimeMillis();
        mus.add(String.format("%.2f", m.buffer.lastBufferFactor));
        for (int i = 1; i < b.size(); i++) {
            m.processBatch(b.get(i));
            mus.add(String.format("%.2f", m.buffer.lastBufferFactor));
        }
        long tEnd = System.currentTimeMillis();
        Res res = new Res();
        res.ms = tEnd - t0;
        res.buildMs = tBuild - t0;
        res.incrMs = tEnd - tBuild;
        res.hs = m.getHighUtilityPatterns().size();
        res.shs = m.bufferedCount();
        res.hsKeys = new ArrayList<>(m.getHighUtilityPatterns().keySet());
        res.calls = m.matchCalls.sum();
        res.miss = m.matchMisses.sum();
        res.mus = mus.toString();
        m.close();
        return res;
    }

    /** Baseline AlgoRIncHUSP Fix(0.4), sequential (its native mode): warm-up + timed. Returns {ms, hs, shs}. */
    static long[] measureBaseline(List<List<List<int[]>>> b, double d, double r, int totalN) {
        runBaseline(b, d, r, totalN);                        // warm-up
        return runBaseline(b, d, r, totalN);
    }

    static long[] runBaseline(List<List<List<int[]>>> b, double d, double r, int totalN) {
        AlgoRIncHUSP m = new AlgoRIncHUSP();
        m.bufferFactor = ExpConfig.muMin;                    // 0.4
        long t0 = System.currentTimeMillis();
        m.hintTotalSequences(totalN);
        m.initialBuild(b.get(0), d, r);
        for (int i = 1; i < b.size(); i++) m.processBatch(b.get(i));
        long ms = System.currentTimeMillis() - t0;
        long hs = m.getHighUtilityPatterns().size();
        long shs = m.bufferedCount();
        m.close();
        return new long[]{ms, hs, shs};
    }

    static double pct(long a, long b) { return b == 0 ? 0.0 : 100.0 * a / b; }

    static int symDiff(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a); u.addAll(b);
        Set<String> i = new TreeSet<>(a); i.retainAll(b);
        return u.size() - i.size();
    }

    static final class Res {
        long ms, buildMs, incrMs, calls, miss;
        int hs, shs;
        List<String> hsKeys;
        String mus;
    }
}
