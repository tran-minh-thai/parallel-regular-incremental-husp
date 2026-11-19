package test;

import algorithms.AlgoRHUSPMinerParallel;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Measures the output rate (patterns per second of enumeration) of the discovery engine, configured as a discovery
 * attempt configures it, on synthetic windows built to make that rate as high as possible: every non-empty subsequence of
 * a sequence of k distinct items with utility 1 is a pattern at threshold 1, so the output is 2^k - 1 patterns.
 *
 * <pre>java -Xmx32g -Dthreads=16 -cp out test.SelectorCalibration OUT_FILE [k=22] [reps=5] [warmups=2]</pre>
 *
 * Shapes: (i) one sequence, k events of one item; (ii) one sequence, k/2 events of two items; (iii) 64 copies of (i).
 * The rate written to OUT_FILE ("rate=<value>" on the first line) is the largest over every shape and measured run; the
 * runs, the command and the environment follow. Self-check: each run must output exactly its expected pattern count,
 * or the tool exits 1 and writes nothing.
 */
public class SelectorCalibration {
    public static void main(String[] a) throws Exception {
        if (a.length < 1) { System.err.println("usage: SelectorCalibration OUT_FILE [k] [reps] [warmups]"); System.exit(2); }
        File out = new File(a[0]);
        int k = a.length > 1 ? Integer.parseInt(a[1]) : 22;
        int reps = a.length > 2 ? Integer.parseInt(a[2]) : 5;
        int warm = a.length > 3 ? Integer.parseInt(a[3]) : 2;
        int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
        String[] names = {"i: one sequence, k single-item events", "ii: one sequence, k/2 two-item events", "iii: 64 copies of (i)"};
        List<List<List<int[]>>> shapes = new ArrayList<>();
        shapes.add(copies(singles(k), 1));
        shapes.add(copies(pairs(k), 1));
        shapes.add(copies(singles(k), 64));
        long[] expected = {(1L << k) - 1, pairsExpected(k), (1L << k) - 1};
        StringBuilder runs = new StringBuilder();
        double best = 0;
        for (int sIdx = 0; sIdx < shapes.size(); sIdx++) {
            for (int r = 0; r < warm + reps; r++) {
                AlgoRHUSPMinerParallel e = engine(threads);
                e.runAlgorithmInMemory(shapes.get(sIdx), 1e-12, 1.0);
                long enumMs = Math.max(0, e.endTimestamp - e.startTimestamp - e.preprocessMs);
                long got = e.finalPatterns.size();
                if (got != expected[sIdx]) {
                    System.err.printf("FAILED: shape %s output %d patterns, expected %d%n", names[sIdx], got, expected[sIdx]);
                    System.exit(1);
                }
                double rate = enumMs > 0 ? got / (enumMs / 1000.0) : Double.NaN;
                String line = String.format(Locale.ROOT, "%s run %d%s: patterns %d enum_ms %d rate %.1f", names[sIdx], r,
                        r < warm ? " (warm-up, not counted)" : "", got, enumMs, rate);
                System.out.println(line);
                runs.append(line).append('\n');
                if (r >= warm && enumMs > 0) best = Math.max(best, rate);
                e.finalPatterns.clear();
            }
        }
        if (!(best > 0)) { System.err.println("FAILED: no measured run had an enumeration time above 0 ms"); System.exit(1); }
        String src = "src/main/java/test/SelectorCalibration.java";
        String text = "rate=" + String.format(Locale.ROOT, "%.1f", best) + "\n"
                + "about=largest output rate (patterns per second of enumeration) over shapes i-iii, k=" + k + ", " + reps
                + " measured runs after " + warm + " warm-ups each\n"
                + "command=java -Xmx" + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "m -Dthreads=" + threads
                + " test.SelectorCalibration " + String.join(" ", a) + "\n"
                + "sha256_SelectorCalibration=" + sha(src) + "\n"
                + "sha256_AlgoRHUSPMinerParallel=" + sha("src/main/java/algorithms/AlgoRHUSPMinerParallel.java") + "\n"
                + "java=" + System.getProperty("java.runtime.version") + " " + System.getProperty("java.vendor") + "\n"
                + "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") + "\n"
                + "threads=" + threads + "\n"
                + "written=" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()) + "\n"
                + runs;
        if (out.getParentFile() != null) out.getParentFile().mkdirs();
        Files.write(out.toPath(), text.getBytes("UTF-8"));
        System.out.printf(Locale.ROOT, "rate=%.1f written to %s%n", best, out.getPath().replace('\\', '/'));
    }

    /** The engine as a discovery attempt configures it (AlgoPRIncHUSP.discoverFrom), at threshold 1 and a loose bound. */
    static AlgoRHUSPMinerParallel engine(int threads) {
        AlgoRHUSPMinerParallel e = new AlgoRHUSPMinerParallel();
        e.numThreads = threads;
        e.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
        e.denseBuffers = true;
        e.useEUCS = true;
        e.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
        e.useRegPruning = true;
        e.windowIsSuffix = true;
        e.windowMode = true;
        e.floorPrune = true;
        e.measureMemory = false;
        e.forcedMinUtil = 1;
        e.forcedMaxReg = 1000;
        return e;
    }

    static List<int[]> singles(int k) {
        List<int[]> s = new ArrayList<>();
        for (int i = 0; i < k; i++) s.add(new int[]{i + 1, 1});
        return s;
    }

    static List<int[]> pairs(int k) {
        List<int[]> s = new ArrayList<>();
        for (int i = 0; i < k; i += 2) s.add(new int[]{i + 1, 1, i + 2, 1});
        return s;
    }

    /** Patterns of shape (ii): each of the k/2 events contributes none, either item, or both, so 4^(k/2) - 1. */
    static long pairsExpected(int k) {
        return (1L << (2 * (k / 2))) - 1;
    }

    static List<List<int[]>> copies(List<int[]> s, int n) {
        List<List<int[]>> w = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<int[]> c = new ArrayList<>();
            for (int[] ev : s) c.add(ev.clone());
            w.add(c);
        }
        return w;
    }

    static String sha(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(Files.readAllBytes(new File(path).toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
