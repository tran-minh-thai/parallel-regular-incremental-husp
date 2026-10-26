package test;

import algorithms.AlgoRHUSPMinerParallel;
import algorithms.Prof;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Audit of the item co-occurrence structure (pair table and adjacency lists) used by the parallel engine.
 * Runs the engine once with the original structure (single-map construction, two-way i-lists, utilities
 * stored) and once per compact construction (hash, dense, partitioned, sketch), and checks that every
 * compact run keeps exactly the same pair set as the compact hash run and returns exactly the same
 * patterns (utility and period) as the original. Prints the structure counts and the phase profile.
 *
 * <pre>
 *   java -Dprof=true -cp out test.EucsAudit seq eui delta absB [threads] [window_fraction] [builds]
 * </pre>
 * With window_fraction w in (0,1) the input is the last w of the sequences mined as a query-time window
 * (window mode, suffix pruning, threshold ceil(delta * U(window))); 0 means the whole database at delta.
 * builds is a comma list (default hash,dense,partitioned,sketch). Exit status is non-zero when any run
 * disagrees or nothing was compared.
 */
public class EucsAudit {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double delta = Double.parseDouble(args[2]);
        int absB = Integer.parseInt(args[3]);
        int threads = args.length > 4 ? Integer.parseInt(args[4]) : Runtime.getRuntime().availableProcessors();
        double w = args.length > 5 ? Double.parseDouble(args[5]) : 0;
        String[] builds = (args.length > 6 ? args[6] : "hash,dense,partitioned,sketch").split(",");
        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<int[]>> db = w > 0 && w < 1 ? new ArrayList<>(all.subList((int) (all.size() * (1 - w)), all.size())) : all;
        long minUtil = (long) Math.ceil(delta * ExpUtil.totalUtil(db));
        System.out.printf("%s N=%d input=%d delta=%s B=%d threads=%d mode=%s minUtil=%d builds=%s%n", ExpUtil.datasetTag(seq),
                all.size(), db.size(), args[2], absB, threads, w > 0 ? "window " + w : "full", minUtil, String.join(",", builds));

        AlgoRHUSPMinerParallel ref = run(db, delta, absB, threads, w, false, AlgoRHUSPMinerParallel.EUCS_HASH, "original");
        long[] refPairs = null;
        int failing = 0, compared = 0;
        for (String b : builds) {
            int mode = Arrays.asList(AlgoRHUSPMinerParallel.EUCS_BUILD_NAMES).indexOf(b);
            if (mode < 0) throw new IllegalArgumentException("unknown build " + b);
            AlgoRHUSPMinerParallel e = run(db, delta, absB, threads, w, true, mode, "compact-" + b);
            int[] d = diff(ref.finalPatterns, e.finalPatterns);
            long[] pairs = e.keptPairKeys();
            if (refPairs == null) refPairs = pairs;
            boolean samePairs = Arrays.equals(refPairs, pairs);
            int pairDiff = samePairs ? 0 : symmetricDifference(refPairs, pairs);
            System.out.printf("  compare with original: patterns=%d missing=%d extra=%d valueMismatch=%d | kept pairs=%d vs first compact=%d, differing=%d%n",
                    ref.finalPatterns.size(), d[0], d[1], d[2], pairs.length, refPairs.length, pairDiff);
            compared++;
            if (d[0] + d[1] + d[2] > 0 || !samePairs) failing++;
        }
        System.out.printf("%ncompared builds=%d failing=%d (reference patterns=%d)%n", compared, failing, ref.finalPatterns.size());
        if (compared == 0 || ref.finalPatterns.isEmpty() || failing > 0) System.exit(1);
    }

    static AlgoRHUSPMinerParallel run(List<List<int[]>> db, double delta, int absB, int threads, double w,
                                      boolean compact, int build, String label) {
        Prof.reset();
        AlgoRHUSPMinerParallel e = new AlgoRHUSPMinerParallel();
        e.numThreads = threads;
        e.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
        e.useEUCS = true;
        e.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
        e.useRegPruning = true;
        e.measureMemory = false;
        e.forcedMaxReg = absB;
        e.forcedMinUtil = (long) Math.ceil(delta * ExpUtil.totalUtil(db));
        if (w > 0) { e.windowMode = true; e.windowIsSuffix = true; }
        e.compactEucs = compact;
        e.eucsBuild = build;
        long t0 = System.nanoTime();
        e.runAlgorithmInMemory(db, delta, 0.0);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%n[%s] built=%s patterns=%d wall=%dms numValid=%d%n", label,
                AlgoRHUSPMinerParallel.EUCS_BUILD_NAMES[Math.max(0, e.eucsBuildUsed)], e.finalPatterns.size(), ms, e.getNumValid());
        System.out.printf("  pairs: i distinct=%d kept=%d | s distinct=%d kept=%d%s%n", e.eucsDistinctI, e.eucsKeptI,
                e.eucsDistinctS, e.eucsKeptS, e.eucsSketchCells > 0
                        ? String.format(" | sketch cells=%d dropped pair-occurrences=%d (distinct counts are survivors)", e.eucsSketchCells, e.eucsSketchDropped) : "");
        System.out.printf("  construction: thread slots=%d (%.1f MB at 20 B/slot, 8 B/cell dense) merged slots=%d (%.1f MB)%n",
                e.eucsThreadMapSlots, e.eucsThreadMapSlots * (e.eucsBuildUsed == AlgoRHUSPMinerParallel.EUCS_DENSE ? 8 : 20) / 1048576.0,
                e.eucsMergedSlots, e.eucsMergedSlots * 20 / 1048576.0);
        System.out.printf("  adjacency: entries i=%d s=%d bytes=%d (%.2f MB)%n", e.eucsPairsI, e.eucsPairsS, e.eucsAdjBytes, e.eucsAdjBytes / 1048576.0);
        System.out.printf("  mining: nodes=%d candidates=%d prunedEucs=%d prunedBound=%d adjEntriesRead=%d deadReverseI=%d%n",
                e.exploredNodes, e.candidateCount, e.prunedEucs, e.prunedBound, e.eucsVisited, e.eucsDeadReverse);
        if (Prof.ON) for (String ln : Prof.report(threads).split("\n")) if (ln.startsWith("engine.eucs")) System.out.println("  " + ln);
        return e;
    }

    static int[] diff(Map<String, AlgoRHUSPMinerParallel.PatternResult> a, Map<String, AlgoRHUSPMinerParallel.PatternResult> b) {
        int miss = 0, extra = 0, val = 0;
        for (Map.Entry<String, AlgoRHUSPMinerParallel.PatternResult> en : a.entrySet()) {
            AlgoRHUSPMinerParallel.PatternResult o = b.get(en.getKey());
            if (o == null) miss++;
            else if (o.utility != en.getValue().utility || o.periodicity != en.getValue().periodicity) val++;
        }
        for (String k : b.keySet()) if (!a.containsKey(k)) extra++;
        return new int[]{miss, extra, val};
    }

    static int symmetricDifference(long[] x, long[] y) {
        int i = 0, j = 0, d = 0;
        while (i < x.length && j < y.length) {
            if (x[i] == y[j]) { i++; j++; } else if (x[i] < y[j]) { d++; i++; } else { d++; j++; }
        }
        return d + (x.length - i) + (y.length - j);
    }
}
