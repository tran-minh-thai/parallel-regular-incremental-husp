package test;

import algorithms.AlgoRHUSPMinerParallel;
import algorithms.Prof;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Audit of the item co-occurrence structure (pair table and adjacency lists) used by the parallel engine.
 * Runs the engine twice on the same input, once with the original lists and once with the compact ones,
 * checks that both return exactly the same patterns (utility and period), and prints the structure counts:
 * distinct pairs, pairs reaching minUtility, construction slots, adjacency bytes, entries read while mining.
 *
 * <pre>
 *   java -Dprof=true -cp out test.EucsAudit seq eui delta absB [threads] [window_fraction]
 * </pre>
 * With window_fraction w in (0,1) the input is the last w of the sequences mined as a query-time window
 * (window mode, suffix pruning, threshold ceil(delta * U(window))); otherwise the whole database at delta.
 * Exit status is non-zero when the two runs disagree or nothing was compared.
 */
public class EucsAudit {

    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double delta = Double.parseDouble(args[2]);
        int absB = Integer.parseInt(args[3]);
        int threads = args.length > 4 ? Integer.parseInt(args[4]) : Runtime.getRuntime().availableProcessors();
        double w = args.length > 5 ? Double.parseDouble(args[5]) : 0;
        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<int[]>> db = w > 0 && w < 1 ? new ArrayList<>(all.subList((int) (all.size() * (1 - w)), all.size())) : all;
        long minUtil = (long) Math.ceil(delta * ExpUtil.totalUtil(db));
        System.out.printf("%s N=%d input=%d delta=%s B=%d threads=%d mode=%s minUtil=%d%n", ExpUtil.datasetTag(seq),
                all.size(), db.size(), args[2], absB, threads, w > 0 ? "window " + w : "full", minUtil);

        AlgoRHUSPMinerParallel[] r = new AlgoRHUSPMinerParallel[2];
        for (int v = 0; v < 2; v++) {
            Prof.reset();
            AlgoRHUSPMinerParallel e = new AlgoRHUSPMinerParallel();
            e.numThreads = threads;
            e.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
            e.useEUCS = true;
            e.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
            e.useRegPruning = true;
            e.measureMemory = false;
            e.forcedMaxReg = absB;
            e.forcedMinUtil = minUtil;
            if (w > 0) { e.windowMode = true; e.windowIsSuffix = true; }
            e.compactEucs = v == 1;
            long t0 = System.nanoTime();
            e.runAlgorithmInMemory(db, delta, 0.0);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            r[v] = e;
            System.out.printf("%n[%s] patterns=%d wall=%dms numValid=%d%n", v == 0 ? "original" : "compact",
                    e.finalPatterns.size(), ms, e.getNumValid());
            System.out.printf("  pairs: i distinct=%d kept=%d | s distinct=%d kept=%d%n",
                    e.eucsDistinctI, e.eucsKeptI, e.eucsDistinctS, e.eucsKeptS);
            System.out.printf("  construction: thread-map slots=%d (%.1f MB) merged slots=%d (%.1f MB)%n",
                    e.eucsThreadMapSlots, e.eucsThreadMapSlots * 20 / 1048576.0,
                    e.eucsMergedSlots, e.eucsMergedSlots * 20 / 1048576.0);
            System.out.printf("  adjacency: entries i=%d s=%d bytes=%d (%.2f MB)%n",
                    e.eucsPairsI, e.eucsPairsS, e.eucsAdjBytes, e.eucsAdjBytes / 1048576.0);
            System.out.printf("  mining: nodes=%d candidates=%d prunedEucs=%d prunedBound=%d adjEntriesRead=%d deadReverseI=%d%n",
                    e.exploredNodes, e.candidateCount, e.prunedEucs, e.prunedBound, e.eucsVisited, e.eucsDeadReverse);
            if (Prof.ON) System.out.print(Prof.report(threads));
        }
        int miss = 0, extra = 0, diff = 0;
        Map<String, AlgoRHUSPMinerParallel.PatternResult> a = r[0].finalPatterns, b = r[1].finalPatterns;
        for (Map.Entry<String, AlgoRHUSPMinerParallel.PatternResult> e : a.entrySet()) {
            AlgoRHUSPMinerParallel.PatternResult o = b.get(e.getKey());
            if (o == null) miss++;
            else if (o.utility != e.getValue().utility || o.periodicity != e.getValue().periodicity) diff++;
        }
        for (String k : b.keySet()) if (!a.containsKey(k)) extra++;
        System.out.printf("%ncompared patterns=%d missing=%d extra=%d valueMismatch=%d%n", a.size(), miss, extra, diff);
        if (a.isEmpty() || miss + extra + diff > 0) System.exit(1);
    }
}
