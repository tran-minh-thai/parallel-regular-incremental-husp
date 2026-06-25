package test;

import algorithms.IncrementalHUSPMiner;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * <h1>OFFICIAL experiment — PARALLEL study</h1>
 *
 * Each benchmark point: WARM-UP (1) + MEASURE {@code measuredRuns} runs + 60-minute TIMEOUT per run.
 * Writes METRICS ONLY to CSV. Baseline = RIncHusp [Ishita2022] Fix(μ) (does NOT use a non-regular
 * miner — not the same problem, not a fair comparison). Recall ground truth = RHusp remining the
 * full DB.
 *
 * <h3>Four scenarios</h3>
 * <ul>
 *   <li><b>S1 — Scalability:</b> P-RIncHUSP sweeps T∈{1,2,4,8,…} (distribution A). Speedup S(p)=T₁/Tₚ, Efficiency E=S/p.</li>
 *   <li><b>S2 — Comparison:</b> P-RIncHUSP(best T) vs Adaptive-seq(T₁) vs RIncHusp Fix(0.4)/Fix(0.9).</li>
 *   <li><b>S3 — Correctness invariance:</b> HS Found is IDENTICAL across all T (deterministic parallelism). Checked within S1.</li>
 *   <li><b>S4 — Distribution robustness:</b> 4 batch distributions A/B/C/D × (P-RIncHUSP vs RIncHusp Fix(0.4)).</li>
 * </ul>
 *
 * <h3>How to run</h3>
 * No arguments → runs {@link DatasetCatalog#officialSuite()} (into a single CSV). With arguments:
 * {@code ExperimentOfficial <seqFile> <eutilFile> [δ=0.002] [ρ=0.30] [outCsv]}.
 */
public class ExperimentOfficial {

    static List<List<int[]>> all;
    static String tag;
    static BufferedWriter csv;

    static final String HEADER =
        "dataset,scenario,distribution,algorithm,mu,minUtilRatio,maxRegRatio,threads,n_batches,iteration,runtime_ms,peak_mb,hs_count,shs_count,recall,status\n";

    public static void main(String[] args) throws IOException {
        if (args.length >= 2) {
            double d = args.length >= 3 ? Double.parseDouble(args[2]) : 0.002;
            double r = args.length >= 4 ? Double.parseDouble(args[3]) : 0.30;
            String tg = ExpUtil.datasetTag(args[0]);
            new File("results").mkdirs();
            String out = args.length >= 5 ? args[4] : "results/official_" + tg + ".csv";
            try (BufferedWriter w = openCsv(out)) { csv = w; benchmarkDataset(tg, args[0], args[1], d, r, false); }
            System.out.println("CSV written: " + out);
            return;
        }
        List<DatasetSpec> suite = DatasetCatalog.officialSuite();
        new File("results").mkdirs();
        String out = "results/official_suite_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new java.util.Date()) + ".csv";
        System.out.printf("### PARALLEL EXPERIMENT — %d datasets -> %s ###%n", suite.size(), out);
        try (BufferedWriter w = openCsv(out)) {
            csv = w;
            for (DatasetSpec s : suite) {
                try { benchmarkDataset(s.tag, s.seqFile, s.euiFile, s.minUtilRatio, s.maxRegRatio, s.s1Only); }
                catch (Throwable e) { System.out.println("  ! Dataset error " + s.tag + ": " + e.getClass().getSimpleName()); }
                finally { all = null; System.gc(); }
            }
        }
        System.out.println("### DONE. CSV: " + out + " ###");
    }

    static BufferedWriter openCsv(String path) throws IOException {
        BufferedWriter w = new BufferedWriter(new FileWriter(path));
        w.write(HEADER);
        return w;
    }

    /**
     * Run S1–S4 for one dataset. The oracle (RHusp over the full DB) does NOT change with the batch
     * distribution → computed once.
     * {@code s1Only=true} (HEAVY datasets: FIFA/KOSARAK) → run S1 scalability only, skip S2/S4 (avoid hours).
     */
    static void benchmarkDataset(String tg, String seqFile, String euiFile,
                                 double minUtilRatio, double maxRegRatio, boolean s1Only) throws IOException {
        tag = tg;
        all = ExpUtil.loadAll(seqFile, euiFile);
        final int cores = Runtime.getRuntime().availableProcessors();
        Set<String> oracle = oracleOrNull(minUtilRatio, maxRegRatio);

        System.out.printf("%n========== %s%s | %d sequences | δ=%.4f ρ=%.2f | %s | %d cores ==========%n",
                tag, s1Only ? " [S1 ONLY]" : "", all.size(), minUtilRatio, maxRegRatio,
                oracle != null ? "oracle(RHusp)=" + oracle.size() : "recall=SKIPPED (N>" + ExpConfig.coverageMaxN + ")", cores);

        List<List<List<int[]>>> bA = ExpUtil.split(all, ExpConfig.SCEN_A);

        // ---------- S1: Scalability + S3: correctness invariance ----------
        if (ExpConfig.runS1Scalability) {
            System.out.println("-- S1 Scalability (distribution A) --");
            int[] ts = threadCounts(cores);
            long[] med = new long[ts.length];
            List<Integer> hsByT = new ArrayList<>();
            for (int i = 0; i < ts.length; i++) {
                final int p = ts[i];
                Agg a = benchmark("S1-scalability", "A-Uniform", "P-RIncHUSP", "adaptive",
                        () -> ExpConfig.newProposed(p), p, bA, minUtilRatio, maxRegRatio, oracle);
                med[i] = a.medianMs;
                if (a.ok) hsByT.add(a.hs);
            }
            printSpeedup(ts, med);
            // S3: HS must be IDENTICAL across all T (parallelism must not change results)
            boolean inv = hsByT.stream().distinct().count() <= 1;
            System.out.printf("   S3 HS invariance across T: %s (%s)%n",
                    inv ? "OK" : "MISMATCH", hsByT);
        }

        // ---------- S2: Compare proposed vs baseline (SKIP for S1-only datasets) ----------
        if (ExpConfig.runS2Compare && !s1Only) {
            System.out.println("-- S2 Comparison (distribution A, best T=" + cores + ") --");
            benchmark("S2-compare", "A-Uniform", "P-RIncHUSP", "adaptive",
                    () -> ExpConfig.newProposed(cores), cores, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S2-compare", "A-Uniform", "Adaptive-seq", "adaptive",
                    () -> ExpConfig.newProposed(1), 1, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S2-compare", "A-Uniform", "RIncHusp-Fix0.4", "0.40",
                    () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S2-compare", "A-Uniform", "RIncHusp-Fix0.9", "0.90",
                    () -> ExpConfig.newRIncHusp(ExpConfig.muFixHigh), 1, bA, minUtilRatio, maxRegRatio, oracle);
        }

        // ---------- S4: Robustness across 4 distributions ----------
        if (ExpConfig.runS4Distribution && !s1Only) {
            System.out.println("-- S4 Distribution robustness (best T=" + cores + ") --");
            for (int s = 0; s < ExpConfig.DIST_NAMES.length; s++) {
                String dist = ExpConfig.DIST_NAMES[s];
                List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.DIST_RATIOS[s]);
                benchmark("S4-distribution", dist, "P-RIncHUSP", "adaptive",
                        () -> ExpConfig.newProposed(cores), cores, b, minUtilRatio, maxRegRatio, oracle);
                benchmark("S4-distribution", dist, "RIncHusp-Fix0.4", "0.40",
                        () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, b, minUtilRatio, maxRegRatio, oracle);
            }
        }
    }

    /** Oracle = RHusp remining the full DB (recall denominator). null if N>coverageMaxN or OOM/timeout. */
    static Set<String> oracleOrNull(double d, double r) {
        if (all.size() > ExpConfig.coverageMaxN) return null;
        ExecutorService ex = Executors.newSingleThreadExecutor(daemon());
        Future<Set<String>> f = ex.submit(() -> ExpUtil.oracleCanon(all, d, r));
        try { return f.get(ExpConfig.runTimeoutMs, TimeUnit.MILLISECONDS); }
        catch (Throwable t) { f.cancel(true); return null; }
        finally { ex.shutdownNow(); }
    }

    /** Warm-up + measure measuredRuns times; write 1 row per run; return aggregate (median, hs, shs, recall). */
    static Agg benchmark(String scenario, String dist, String algo, String mu, Supplier<IncrementalHUSPMiner> factory,
                         int threads, List<List<List<int[]>>> b, double d, double r, Set<String> oracle) throws IOException {
        Run warm = null;
        for (int w = 0; w < Math.max(1, ExpConfig.warmupRuns); w++) warm = timedRun(factory, b, d, r);
        if (warm.timedOut || warm.error != null || warm.patterns == null) {
            writeRow(scenario, dist, algo, mu, d, r, threads, b.size(), 0, warm, "");
            System.out.printf("   [%-15s %-15s T=%-2d] %s%n", scenario, algo, threads,
                    warm.timedOut ? "TIMEOUT" : "ERROR:" + warm.error);
            return Agg.failed();
        }
        String recall = oracle != null ? String.format("%.4f", ExpUtil.coverage(warm.patterns, oracle)) : "";
        long[] times = new long[ExpConfig.measuredRuns]; int n = 0; Run last = warm;
        for (int it = 1; it <= ExpConfig.measuredRuns; it++) {
            System.gc();
            Run rr = timedRun(factory, b, d, r);
            writeRow(scenario, dist, algo, mu, d, r, threads, b.size(), it, rr, recall);
            last = rr;
            if (rr.timedOut) break;
            times[n++] = rr.runtimeMs;
        }
        long median = n > 0 ? median(Arrays.copyOf(times, n)) : -1;
        System.out.printf("   [%-15s %-15s T=%-2d] %6d ms | %7.1f MB | HS=%d SHS=%d%s%n",
                scenario, algo, threads, median, last.peakMb, last.count, last.shs,
                recall.isEmpty() ? "" : " | recall=" + recall);
        return new Agg(true, median, last.count, last.shs);
    }

    static Run timedRun(Supplier<IncrementalHUSPMiner> factory, List<List<List<int[]>>> b, double d, double r) {
        ExecutorService ex = Executors.newSingleThreadExecutor(daemon());
        IncrementalHUSPMiner m = factory.get();
        Callable<Run> task = () -> {
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(m, b, d, r);
            Run rr = new Run();
            rr.runtimeMs = System.currentTimeMillis() - t0;
            rr.peakMb = m.peakMemoryMB();
            rr.count = res.size();
            rr.shs = m.bufferedCount();
            rr.patterns = res;
            return rr;
        };
        Future<Run> f = ex.submit(task);
        try { return f.get(ExpConfig.runTimeoutMs, TimeUnit.MILLISECONDS); }
        catch (TimeoutException te) { f.cancel(true); Run x = new Run(); x.timedOut = true; return x; }
        catch (Throwable e) {
            Run x = new Run();
            Throwable c = (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null) ? e.getCause() : e;
            x.error = c.getClass().getSimpleName();
            return x;
        } finally { ex.shutdownNow(); }
    }

    static void writeRow(String scenario, String dist, String algo, String mu, double d, double r,
                         int threads, int nb, int iter, Run run, String recall) throws IOException {
        String status = run.timedOut ? "TIMEOUT" : (run.error != null ? "ERROR" : "OK");
        csv.write(String.format("%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%.2f,%d,%d,%s,%s%n",
                tag, scenario, dist, algo, mu, d, r, threads, nb, iter,
                run.timedOut ? -1 : run.runtimeMs,
                run.timedOut ? 0.0 : run.peakMb,
                run.timedOut ? -1 : run.count,
                run.timedOut ? -1 : run.shs,
                recall, status));
        csv.flush();
    }

    /** Print the speedup table S(p)=T₁/Tₚ and efficiency E(p)=S/p (relative to T=1). */
    static void printSpeedup(int[] ts, long[] med) {
        if (med.length == 0 || med[0] <= 0) return;
        double t1 = med[0];
        System.out.println("   ── Speedup ──  T :  ms  | S(p) | E(p)");
        for (int i = 0; i < ts.length; i++) {
            if (med[i] <= 0) continue;
            double s = t1 / med[i], e = s / ts[i];
            System.out.printf("                %2d : %6d | %4.2f | %4.2f%n", ts[i], med[i], s, e);
        }
    }

    static long median(long[] a) { Arrays.sort(a); int n = a.length; return n == 0 ? -1 : (n % 2 == 1 ? a[n/2] : (a[n/2-1]+a[n/2])/2); }

    static java.util.concurrent.ThreadFactory daemon() {
        return t -> { Thread th = new Thread(t); th.setDaemon(true); return th; };
    }

    static int[] threadCounts(int cores) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        for (int p = 1; p <= cores; p <<= 1) s.add(p);
        s.add(cores);
        int[] a = new int[s.size()]; int i = 0; for (int v : s) a[i++] = v; return a;
    }

    static final class Run {
        long runtimeMs; double peakMb; int count; int shs;
        Map<String, long[]> patterns;
        boolean timedOut = false; String error = null;
    }

    /** Aggregate of one benchmark point (after multiple measured runs). */
    static final class Agg {
        final boolean ok; final long medianMs; final int hs; final int shs;
        Agg(boolean ok, long medianMs, int hs, int shs) { this.ok = ok; this.medianMs = medianMs; this.hs = hs; this.shs = shs; }
        static Agg failed() { return new Agg(false, -1, -1, -1); }
    }
}
