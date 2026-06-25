package test;
import algorithms.*;


import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SHARED utilities for the two experiment classes {@link ExperimentTest} (test, saves patterns)
 * and {@link ExperimentOfficial} (official, writes metrics only). Includes: loading/splitting
 * batches, incremental runs, building the RHusp oracle, canonicalizing keys, computing coverage,
 * and writing patterns to file.
 */
public final class ExpUtil {
    private ExpUtil() {}

    public static List<List<int[]>> loadAll(String seqFile, String euiFile) throws IOException {
        QSeqDatabase db = new QSeqDatabase();
        db.loadExternalUtility(euiFile);
        return db.readSequences(seqFile);
    }

    /** Split {@code all} into batches by ratio (the last batch receives the remainder). */
    public static List<List<List<int[]>>> split(List<List<int[]>> all, double[] ratios) {
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

    /** Default batch-split ratios (single-dataset CLI mode) = scenario A (uniform). */
    public static double[] defaultRatios() { return ExpConfig.SCEN_A; }

    /** initialBuild(batch0) then processBatch(batch1..k); return the current high-utility pattern set. */
    public static Map<String, long[]> run(IncrementalHUSPMiner m, List<List<List<int[]>>> b,
                                          double minUtilRatio, double maxRegRatio) {
        try {
            int totalN = 0; for (List<List<int[]>> batch : b) totalN += batch.size();
            m.hintTotalSequences(totalN);              // ρ·N_final for sound regularity pruning at seeding time
            m.initialBuild(b.get(0), minUtilRatio, maxRegRatio);
            for (int i = 1; i < b.size(); i++) m.processBatch(b.get(i));
            // copy the result BEFORE close() (which frees shared resources/pool)
            return new java.util.HashMap<>(m.getHighUtilityPatterns());
        } finally {
            m.close();
        }
    }

    /** Exact RHusp oracle over the full DB; returns CANONICALIZED keys. */
    public static Set<String> oracleCanon(List<List<int[]>> all, double minUtilRatio, double maxRegRatio) {
        long totalDbUtility = 0;
        for (List<int[]> s : all) for (int[] e : s) for (int k = 1; k < e.length; k += 2) totalDbUtility += e[k];
        long minUtil = (long) Math.ceil(minUtilRatio * totalDbUtility);
        int maxReg = (int) (maxRegRatio * all.size());
        AlgoRHUSP m = new AlgoRHUSP(); m.parallel = false;
        TreeSet<String> set = new TreeSet<>();
        for (String k : m.mine(all, minUtil, maxReg).keySet()) set.add(SeqConverter.canonical(k));
        return set;
    }

    /** Number of patterns in {@code res} matching the oracle (by canonical key). */
    public static int hits(Map<String, long[]> res, Set<String> oracleCanon) {
        int h = 0;
        for (String k : res.keySet()) if (oracleCanon.contains(SeqConverter.canonical(k))) h++;
        return h;
    }

    public static double coverage(Map<String, long[]> res, Set<String> oracleCanon) {
        return oracleCanon.isEmpty() ? 1.0 : (double) hits(res, oracleCanon) / oracleCanon.size();
    }

    /** Write the pattern set to a txt file (each line: "pattern #UTIL: u #PER: p"), sorted descending by utility. */
    public static void dumpPatterns(Map<String, long[]> patterns, String path) throws IOException {
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(patterns.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            for (Map.Entry<String, long[]> e : rows) {
                long per = e.getValue()[1];
                w.write(e.getKey() + " #UTIL: " + e.getValue()[0]
                        + (per >= 0 ? " #PER: " + per : "") + "\n");
            }
        }
    }

    /** Safe filename tag from a dataset path (take the name, drop the extension). */
    public static String datasetTag(String seqFile) {
        String base = new java.io.File(seqFile).getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base.replace("_seq", "");
    }

    public static long totalUtil(List<List<int[]>> all) {
        long totalDbUtility = 0;
        for (List<int[]> s : all) for (int[] e : s) for (int k = 1; k < e.length; k += 2) totalDbUtility += e[k];
        return totalDbUtility;
    }
}
