package test;
import algorithms.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Incremental experiment runner: drives the PROPOSED algorithm and BASELINES uniformly on
 * a common batch-split scenario, measuring runtime / memory / coverage.
 *
 * <pre>
 *   Usage: RunIncremental &lt;seqFile&gt; &lt;eutilFile&gt; &lt;minUtilRatio&gt; &lt;maxRegRatio&gt; [batchRatios=0.4,0.2,0.2,0.2]
 * </pre>
 *
 * Workflow: read all sequences -> split into batches per {@code batchRatios} (batch 0 = D_old) ->
 * for each algorithm: initialBuild(D_old) then processBatch(ΔD_i) in order -> print report.
 * Coverage is measured against the ORACLE = {@link AlgoRHUSPRecompute} (full re-mine each batch).
 */
public class RunIncremental {

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.out.println("Usage: RunIncremental <seqFile> <eutilFile> <minUtilRatio> <maxRegRatio> [batchRatios]");
            return;
        }
        String seqFile = args[0], eutilFile = args[1];
        double minUtilRatio = Double.parseDouble(args[2]);
        double maxRegRatio = Double.parseDouble(args[3]);
        double[] ratios = (args.length >= 5) ? parseRatios(args[4])
                                             : new double[]{0.4, 0.2, 0.2, 0.2};

        // ---- load & split ----
        QSeqDatabase loader = new QSeqDatabase();
        loader.loadExternalUtility(eutilFile);
        List<List<int[]>> all = loader.readSequences(seqFile);
        List<List<List<int[]>>> batches = split(all, ratios);
        System.out.printf("Loaded %d sequences -> %d batches %s%n", all.size(), batches.size(),
                java.util.Arrays.toString(sizes(batches)));

        // ---- ORACLE for coverage measurement: RHusp re-mine from scratch (the manuscript, row "RHusp") ----
        AlgoRHUSPRecompute oracle = new AlgoRHUSPRecompute();
        runMiner(oracle, batches, minUtilRatio, maxRegRatio);
        Map<String, long[]> oracleResult = oracle.getHighUtilityPatterns();

        // ---- algorithms to compare (labels match the baseline table in the paper) ----
        AlgoPRIncHUSP proposed = new AlgoPRIncHUSP();          // P-RIncHUSP (PROPOSED)
        AlgoPRIncHUSP proposedSeq = new AlgoPRIncHUSP();       // Proposed-sequential (T=1)
        proposedSeq.numThreads = 1;
        proposedSeq.label = "Proposed-sequential (T=1)";

        List<IncrementalHUSPMiner> miners = new ArrayList<>();
        miners.add(proposed);                                  // P-RIncHUSP
        miners.add(proposedSeq);                               // Proposed-sequential
        miners.add(new AlgoRIncHUSP());                        // RIncHusp [Ishita2022]
        // (oracle = RHusp-Recompute, already run above)

        System.out.println("\n========== RESULTS ==========");
        report("RHusp (oracle/recompute)", oracle, oracleResult, oracleResult);
        for (IncrementalHUSPMiner m : miners) {
            runMiner(m, batches, minUtilRatio, maxRegRatio);
            report(m.name(), m, m.getHighUtilityPatterns(), oracleResult);
        }
    }

    /** initialBuild(batch 0) then processBatch(batch 1..k), printing per-batch time. */
    private static void runMiner(IncrementalHUSPMiner m, List<List<List<int[]>>> batches,
                                 double minUtilRatio, double maxRegRatio) {
        long t0 = System.currentTimeMillis();
        m.initialBuild(batches.get(0), minUtilRatio, maxRegRatio);
        StringBuilder times = new StringBuilder("  [" + m.name() + "] batch0=init");
        for (int i = 1; i < batches.size(); i++) {
            long ms = m.processBatch(batches.get(i));
            times.append(", batch").append(i).append('=').append(ms).append("ms");
        }
        times.append(" | total=").append(System.currentTimeMillis() - t0).append("ms");
        System.out.println(times);
    }

    private static void report(String name, IncrementalHUSPMiner m,
                               Map<String, long[]> result, Map<String, long[]> oracle) {
        int hit = 0;
        for (String k : result.keySet()) if (oracle.containsKey(k)) hit++;
        double coverage = oracle.isEmpty() ? 1.0 : (double) hit / oracle.size();
        System.out.printf("%-30s | HS=%4d | buffer=%4d | mem=%.1fMB | coverage=%.1f%%%n",
                name, result.size(), m.bufferedCount(), m.peakMemoryMB(), coverage * 100);
    }

    // ---- batch split utilities ----
    private static List<List<List<int[]>>> split(List<List<int[]>> all, double[] ratios) {
        List<List<List<int[]>>> out = new ArrayList<>();
        int n = all.size(), from = 0;
        for (int i = 0; i < ratios.length; i++) {
            int cnt = (i == ratios.length - 1) ? (n - from) : (int) Math.round(n * ratios[i]);
            cnt = Math.max(0, Math.min(cnt, n - from));
            out.add(new ArrayList<>(all.subList(from, from + cnt)));
            from += cnt;
        }
        return out;
    }

    private static double[] parseRatios(String s) {
        String[] p = s.split(",");
        double[] r = new double[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Double.parseDouble(p[i].trim());
        return r;
    }

    private static int[] sizes(List<List<List<int[]>>> batches) {
        int[] s = new int[batches.size()];
        for (int i = 0; i < s.length; i++) s[i] = batches.get(i).size();
        return s;
    }
}
