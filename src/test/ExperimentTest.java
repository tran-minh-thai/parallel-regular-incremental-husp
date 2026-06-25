package test;

import algorithms.*;
import common.Sequence;
import common.Sequence;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * <h1>TEST experiment</h1>
 *
 * Run each algorithm ONCE, <b>SAVE THE PATTERN SET to .txt</b> for correctness comparison against
 * the RHusp oracle, and print a coverage table. Does NOT measure performance (that is
 * {@link ExperimentOfficial}'s job).
 *
 * <h3>Two ways to run</h3>
 * <ul>
 *   <li><b>Automatic full suite (IntelliJ — click Run, no arguments):</b> runs
 *       {@link DatasetCatalog#testSuite()} with preconfigured parameters.</li>
 *   <li><b>Single dataset (terminal):</b>
 *       {@code ExperimentTest <seqFile> <eutilFile> [minUtilRatio=0.10] [maxRegRatio=0.6] [outDir=test_output]}</li>
 * </ul>
 */
public class ExperimentTest {

    public static void main(String[] args) throws IOException {
        String outDir = "results/test_patterns";   // all test outputs go under results/
        if (args.length >= 2) {                       // run a single dataset from the command line
            double minUtilRatio = args.length >= 3 ? Double.parseDouble(args[2]) : 0.10;
            double maxRegRatio   = args.length >= 4 ? Double.parseDouble(args[3]) : 0.6;
            if (args.length >= 5) outDir = args[4];
            new File(outDir).mkdirs();
            runOne(ExpUtil.datasetTag(args[0]), args[0], args[1], minUtilRatio, maxRegRatio,
                   ExpUtil.defaultRatios(), outDir);
            return;
        }

        // No arguments -> run the FULL test SUITE (suitable for clicking Run in IntelliJ)
        List<DatasetSpec> suite = DatasetCatalog.testSuite();
        new File(outDir).mkdirs();
        System.out.printf("### TEST EXPERIMENT — %d datasets, patterns saved to '%s/' ###%n", suite.size(), outDir);
        for (DatasetSpec s : suite) {
            try {
                runOne(s.tag, s.seqFile, s.euiFile, s.minUtilRatio, s.maxRegRatio, s.batchRatios, outDir);
            } catch (Exception e) {
                System.out.println("  ! Dataset error " + s.tag + ": " + e.getMessage());
            }
        }
        System.out.println("### TEST DONE ###");
    }

    /** Run a test for one dataset: save patterns from every algorithm + print coverage. */
    static void runOne(String tag, String seqFile, String euiFile, double minUtilRatio, double maxRegRatio,
                       double[] ratios, String outDir) throws IOException {
        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        List<List<List<int[]>>> b = ExpUtil.split(all, ratios);
        Set<String> oracle = ExpUtil.oracleCanon(all, minUtilRatio, maxRegRatio);

        int cores = Runtime.getRuntime().availableProcessors();
        System.out.printf("%n== TEST %s | %d sequences | δ=%.2f ρ=%.2f | μ=%.2f | %d batches | oracle RHusp=%d patterns ==%n",
                tag, all.size(), minUtilRatio, maxRegRatio, ExpConfig.muMin, b.size(), oracle.size());

        dumpOracle(all, minUtilRatio, maxRegRatio, fileName(outDir, "RHusp", tag, minUtilRatio, maxRegRatio));
        System.out.printf("%-24s %6s %6s %9s  -> %s%n", "Algorithm", "HS", "extra+", "coverage", "file");

        // miners created via ExpConfig -> shared μ applied (consistent with the official benchmark)
        testRHUSP("P-RIncHUSP",      ExpConfig.newProposed(cores), b, minUtilRatio, maxRegRatio, oracle, outDir, tag);
        testRHUSP("Sequential-proposed", ExpConfig.newProposed(1), b, minUtilRatio, maxRegRatio, oracle, outDir, tag);
        testRHUSP("RIncHusp",        ExpConfig.newRIncHusp(ExpConfig.muMin), b, minUtilRatio, maxRegRatio, oracle, outDir, tag);
        testHUSP(all, b, minUtilRatio, maxRegRatio, oracle, outDir, tag);
    }

    private static void testRHUSP(String label, IncrementalHUSPMiner m, List<List<List<int[]>>> b,
                                  double minUtilRatio, double maxRegRatio, Set<String> oracle,
                                  String outDir, String tag) throws IOException {
        Map<String, long[]> res = ExpUtil.run(m, b, minUtilRatio, maxRegRatio);
        int hit = ExpUtil.hits(res, oracle), extra = res.size() - hit;
        String path = fileName(outDir, label.replace(" ", "-"), tag, minUtilRatio, maxRegRatio);
        ExpUtil.dumpPatterns(res, path);
        System.out.printf("%-24s %6d %6d %8.1f%%  -> %s%n",
                label, res.size(), extra, 100.0 * ExpUtil.coverage(res, oracle), new File(path).getName());
    }

    /** Sequence-count threshold: above this, SKIP the static HUSP baseline (USpan/HUSP-ULL slow on long sequences). */
    private static final int HUSP_MAX_N = 200;

    private static void testHUSP(List<List<int[]>> all, List<List<List<int[]>>> b,
                                 double minUtilRatio, double maxRegRatio, Set<String> oracle,
                                 String outDir, String tag) throws IOException {
        if (all.size() > HUSP_MAX_N) {
            System.out.printf("-- HUSP baseline: SKIPPED (numSequences=%d > %d, static miner slow on long sequences) --%n",
                    all.size(), HUSP_MAX_N);
            return;
        }
        System.out.println("-- HUSP baseline (non-regular, reported separately) --");
        IncUSPMinerPlusAdapter inc = new IncUSPMinerPlusAdapter();
        Map<String, long[]> incRes = ExpUtil.run(inc, b, minUtilRatio, maxRegRatio);
        ExpUtil.dumpPatterns(incRes, fileName(outDir, "IncUSP-Miner+", tag, minUtilRatio, maxRegRatio));
        System.out.printf("%-24s %6d (covers %d/%d regular patterns)%n", "IncUSP-Miner+", incRes.size(),
                ExpUtil.hits(incRes, oracle), oracle.size());

        List<Sequence> full = SeqConverter.toSequences(all, 0);
        ReferenceMiners.Result us = ReferenceMiners.runUSpan(full, minUtilRatio);
        ReferenceMiners.Result ull = ReferenceMiners.runHUSPULL(full, minUtilRatio);
        dumpSet(us.patterns, fileName(outDir, "USpan", tag, minUtilRatio, maxRegRatio));
        dumpSet(ull.patterns, fileName(outDir, "HUSP-ULL", tag, minUtilRatio, maxRegRatio));
        System.out.printf("%-24s %6d  | %-12s %6d%n", "USpan", us.patternCount, "HUSP-ULL", ull.patternCount);
        System.out.printf("RHUSP=%d subset of HUSP=%d: the regularity constraint prunes %d->%d.%n",
                oracle.size(), us.patternCount, us.patternCount, oracle.size());
    }

    private static void dumpOracle(List<List<int[]>> all, double minUtilRatio, double maxRegRatio, String path) throws IOException {
        long totalDbUtility = ExpUtil.totalUtil(all);
        AlgoRHUSP m = new AlgoRHUSP(); m.parallel = false;
        Map<String, long[]> res = m.mine(all, (long) Math.ceil(minUtilRatio * totalDbUtility), (int) (maxRegRatio * all.size()));
        ExpUtil.dumpPatterns(res, path);
    }

    private static void dumpSet(Set<String> patterns, String path) throws IOException {
        Map<String, long[]> m = new TreeMap<>();
        for (String p : patterns) m.put(p, new long[]{0, -1});
        ExpUtil.dumpPatterns(m, path);
    }

    private static String fileName(String dir, String algo, String tag, double d, double r) {
        return dir + File.separator + algo + "_" + tag + "_su" + d + "_reg" + r + "_patterns.txt";
    }
}
