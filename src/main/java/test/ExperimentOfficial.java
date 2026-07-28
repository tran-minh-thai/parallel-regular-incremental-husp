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
 * Runs the experiment suite and writes one CSV row per measured run.
 *
 * <p>Every benchmark point is one warm-up run followed by a tiered number of measured
 * runs, each under a 60-minute timeout. Only metrics are recorded; the patterns themselves are not
 * written out.
 *
 * <p>Two references matter when reading the numbers. RIncHusp Fix(mu) is the sequential incremental
 * miner this work extends, run at a fixed buffer factor. Recall is measured against RHusp re-mining
 * the whole database, which is the ground truth by definition; miners without the regularity
 * constraint solve a different problem and are not used as baselines. Recall is skipped on datasets
 * larger than {@code ExpConfig.coverageMaxN}, where building that ground truth is impractical.
 *
 * <h2>Scenarios</h2>
 * <ul>
 *   <li><b>S1</b> — scalability: sweep the thread count on distribution A, reporting speedup
 *       S(p) = T1/Tp and efficiency E = S/p.</li>
 *   <li><b>S2</b> — comparison at the best thread count: the proposed miner against its own
 *       sequential variant, the Fix(0.4)/Fix(0.9) baselines, and re-mining every batch (both
 *       sequential and parallel).</li>
 *   <li><b>S3</b> — parallel invariance: pattern count and recall must not change with the thread
 *       count. Checked from the S1 runs rather than measured separately.</li>
 *   <li><b>S4</b> — robustness across the four batch distributions.</li>
 *   <li><b>S5</b> — maintenance strategy: content-driven against the per-pattern inverted index, on
 *       a fine batch schedule. Both return the same patterns, so the gap is maintenance cost alone.</li>
 *   <li><b>S6</b> — sensitivity to the utility threshold delta.</li>
 *   <li><b>S7</b> — sensitivity to the regularity threshold rho, including the approximate variant,
 *       whose recall falls away as rho tightens.</li>
 *   <li><b>S8</b> — batch-count scaling: incremental maintenance against re-mining every batch over
 *       k batches, which locates the point where incremental work becomes the cheaper option.</li>
 *   <li><b>S9</b> — buffer factor sweep: recall is independent of mu, cost is not.</li>
 *   <li><b>S10</b> — exactness ablation: the two seed bounds switched on and off.</li>
 *   <li><b>S11</b> — the S2 comparison repeated at several batch counts, so the ranking is a trend
 *       rather than a point.</li>
 * </ul>
 * S5 to S10 run only on datasets small enough to have an oracle; the heavy ones are marked
 * scalability-only in {@link DatasetCatalog}.
 *
 * <h2>Running</h2>
 * <pre>
 *   java -cp out test.ExperimentOfficial                     # full suite from DatasetCatalog
 *   java -cp out test.ExperimentOfficial --test              # small datasets; finishes in seconds
 *   java -cp out test.ExperimentOfficial --only=SIGN,BIBLE   # a subset of the suite
 *   java -cp out test.ExperimentOfficial --resume            # continue the newest unfinished run
 *   java -cp out test.ExperimentOfficial --s9b               # only the lambda sweep on distribution B
 *   java -cp out test.ExperimentOfficial --m1                # only the per-batch-exact comparison
 *   java -cp out test.ExperimentOfficial --followup          # every follow-up probe, one session
 *   java -cp out test.ExperimentOfficial --absolute          # declared constant B per dataset
 *   java -cp out test.ExperimentOfficial seq.txt eutil.txt [delta] [rho] [out.csv]   # one dataset
 * </pre>
 * Suite runs create {@code results/run_<timestamp>_<id>/} holding the CSV, the dataset statistics
 * and the resume state; see {@link RunContext}.
 */
public class ExperimentOfficial {

    static List<List<int[]>> all;
    static String tag;
    static int specAbsB = 0;        // declared absolute bound of the dataset being benchmarked
    static double specBaseR = 1;    // its base rho, so sweep multipliers scale B coherently

    /** The declared B scaled by a sweep's multiplier, truncating like every bound in this study —
     *  declared numbers only, and the SAME value goes to the miners and to the cell's oracle, since
     *  a bound the two sides disagree on answers two different questions. Zero when not absolute. */
    static int scaledAbsB(double r) {
        return ExpConfig.absoluteMode && specAbsB > 0
                ? Math.max(1, (int) (specAbsB * (r / specBaseR)))
                : 0;
    }
    static BufferedWriter csv;

    static final String HEADER =
        "dataset,scenario,distribution,algorithm,mu,minUtilRatio,maxRegRatio,threads,n_batches,iteration,runtime_ms,build_ms,incr_ms,disc_ms,peak_mb,hs_count,shs_count,recall,status,seed_mb,incr_mb,disc_mb,tracked_seed,tracked,oracle_size,oracle_hits,abs_b\n";

    /** Provenance + crash-resume state for the suite run (null in single-dataset mode). */
    static RunContext ctx;

    public static void main(String[] args) throws IOException {
        java.util.List<String> flags = java.util.Arrays.asList(args);
        // Single-dataset mode: <seqFile> <eutilFile> [δ] [ρ] [outCsv] — first arg is a path, not a --flag.
        if (args.length >= 2 && !args[0].startsWith("--")) {
            double d = args.length >= 3 ? Double.parseDouble(args[2]) : 0.002;
            double r = args.length >= 4 ? Double.parseDouble(args[3]) : 0.30;
            String tg = ExpUtil.datasetTag(args[0]);
            new File("results").mkdirs();
            String out = args.length >= 5 ? args[4] : "results/official_" + tg + ".csv";
            try (BufferedWriter w = openCsv(out)) { csv = w; benchmarkDataset(tg, args[0], args[1], d, r, false); }
            System.out.println("CSV written: " + out);
            return;
        }
        // Suite mode. Flags: --resume (continue newest matching unfinished run), --test (tiny testSuite).
        boolean resume = flags.contains("--resume");
        boolean testMode = flags.contains("--test");
        List<DatasetSpec> suite = testMode ? DatasetCatalog.testSuite() : DatasetCatalog.officialSuite();
        if (testMode) ExpConfig.enableSweepsForTestSuite();   // exercise S6-S10 too, in seconds
        // --s9b: run only the follow-up lambda sweep under the increasing distribution.
        if (flags.contains("--s9b")) ExpConfig.enableS9BOnly();
        // --m1: run only the per-batch-exact comparison (P-RIncHUSP-P against ParRemine).
        if (flags.contains("--m1")) ExpConfig.enableM1Only();
        // --followup: run all three follow-up probes in one session (M1 + single re-mine + S9B).
        if (flags.contains("--followup")) ExpConfig.enableFollowupOnly();
        // --absolute: the regularity constraint becomes each dataset's DECLARED constant B; see ExpConfig.
        if (flags.contains("--absolute")) ExpConfig.absoluteMode = true;

        // --only=TAG1,TAG2 restricts the suite to the named datasets, each keeping its own settings.
        // Useful after changing one dataset's threshold: re-run that dataset alone, then merge the
        // resulting directory over the previous full run as a patch (see the table generator).
        for (String a : args) {
            if (a.startsWith("--only=")) {
                java.util.Set<String> keep = new java.util.HashSet<>(
                        java.util.Arrays.asList(a.substring("--only=".length()).split(",")));
                java.util.List<DatasetSpec> f = new java.util.ArrayList<>();
                for (DatasetSpec s : suite) if (keep.contains(s.tag)) f.add(s);
                suite = f;
                if (suite.isEmpty()) { System.out.println("!! --only matched no datasets: " + keep); return; }
                System.out.println("### --only: partial run of " + suite.size() + " dataset(s): " + keep + " ###");
            }
        }

        ctx = RunContext.start(suite, resume);
        csv = ctx.csv;
        System.out.printf("### PARALLEL EXPERIMENT — %d datasets | %s | dir=results/%s ###%n",
                suite.size(), ctx.resumed ? "RESUME (skipping finished work)" : "fresh run", ctx.dir.getName());
        System.out.printf("### thread sweep (pinned) = %s | best T = %d ###%n",
                Arrays.toString(ExpConfig.effectiveThreadSweep()), ExpConfig.bestT());
        if (ExpConfig.threadSweepTruncated()) {
            String bar = "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!";
            System.out.println(bar);
            System.out.printf("!! WARNING: this machine reports only %d cores, so the pinned sweep %s%n",
                    Runtime.getRuntime().availableProcessors(), Arrays.toString(ExpConfig.THREAD_SWEEP));
            System.out.printf("!! is TRUNCATED to %s. The tables will NOT match the paper's protocol.%n",
                    Arrays.toString(ExpConfig.effectiveThreadSweep()));
            System.out.println("!! macOS: plug in the charger, turn OFF Low Power Mode, and re-run.");
            System.out.println(bar);
        }
        for (DatasetSpec s : suite) {
            if (ctx.isDatasetDone(s.tag)) {
                System.out.printf("%n========== %s — SKIP (already finished in this run) ==========%n", s.tag);
                continue;
            }
            try {
                specAbsB = s.absB; specBaseR = s.maxRegRatio;   // the DECLARED bound travels with the spec
                benchmarkDataset(s.tag, s.seqFile, s.euiFile, s.minUtilRatio, s.maxRegRatio, s.s1Only);
                ctx.recordDataset(s.tag);   // reached only if the dataset finished (no crash / uncaught error)
            } catch (Throwable e) {
                System.out.println("  ! Dataset error " + s.tag + ": " + e.getClass().getSimpleName());
            } finally { all = null; System.gc(); }
        }
        ctx.finish();     // write DONE marker + meta status=completed
        ctx.close();
        System.out.println("### DONE. dir: " + ctx.dir + " ###");
    }

    static BufferedWriter openCsv(String path) throws IOException {
        BufferedWriter w = new BufferedWriter(new FileWriter(path));
        w.write(HEADER);
        return w;
    }

    /**
     * Run every configured scenario for one dataset. The oracle (RHusp over the full DB) does NOT change with the batch
     * distribution → computed once.
     * {@code s1Only=true} (HEAVY datasets: FIFA/KOSARAK) → run S1 scalability only, skip S2/S4 (avoid hours).
     */
    static void benchmarkDataset(String tg, String seqFile, String euiFile,
                                 double minUtilRatio, double maxRegRatio, boolean s1Only) throws IOException {
        tag = tg;
        all = ExpUtil.loadAll(seqFile, euiFile);
        writeDatasetStats(tg, all);                    // characteristics table (once per dataset)
        final int cores = ExpConfig.bestT();           // PINNED best-T (not availableProcessors) — see ExpConfig
        Set<String> oracle = oracleOrNull(minUtilRatio, maxRegRatio);

        if (ExpConfig.absoluteMode) {
            if (specAbsB > 0)
                System.out.printf("%n### ABSOLUTE maxReg: B = %d sequences (declared; constant at every batch) ###%n",
                        specAbsB);
            else
                System.out.printf("%n### !! %s has NO declared B -- this dataset runs in RELATIVE mode inside an"
                        + " absolute run; its rows are labeled by what they measure, not by the flag ###%n", tag);
        }
        System.out.printf("%n========== %s%s | %d sequences | δ=%.4f ρ=%.2f | %s | best T=%d (machine has %d) ==========%n",
                tag, s1Only ? " [S1 ONLY]" : "", all.size(), minUtilRatio, maxRegRatio,
                oracle != null ? "oracle(RHusp)=" + oracle.size() : "recall=SKIPPED (N>" + ExpConfig.coverageMaxN + ")",
                cores, Runtime.getRuntime().availableProcessors());

        List<List<List<int[]>>> bA = ExpUtil.split(all, ExpConfig.SCEN_A);

        // ---------- S1: Scalability + S3: correctness invariance ----------
        if (ExpConfig.runS1Scalability) {
            System.out.println("-- S1 Scalability (distribution A) --");
            int[] ts = ExpConfig.effectiveThreadSweep();   // PINNED sweep (clamped to real cores)
            long[] med = new long[ts.length];
            List<Integer> hsByT = new ArrayList<>();
            for (int i = 0; i < ts.length; i++) {
                final int p = ts[i];
                Agg a = benchmark("S1-scalability", "A-Uniform", "P-RIncHUSP", "trie",
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
            benchmark("S2-compare", "A-Uniform", "P-RIncHUSP", "trie",
                    () -> ExpConfig.newProposed(cores), cores, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S2-compare", "A-Uniform", "P-RIncHUSP-seq", "trie",
                    () -> ExpConfig.newProposed(1), 1, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S2-compare", "A-Uniform", "RIncHusp-Fix0.4", "0.40",
                    () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S2-compare", "A-Uniform", "RIncHusp-Fix0.9", "0.90",
                    () -> ExpConfig.newRIncHusp(ExpConfig.muFixHigh), 1, bA, minUtilRatio, maxRegRatio, oracle);
            // Re-mine baselines (light datasets only — re-mining a heavy DB every batch is prohibitive,
            // which is exactly the cost the incremental methods avoid; see C2/C3 in the design notes).
            //   Remine-static : SEQUENTIAL static miner re-run each batch.
            //   ParRemine     : the companion study's PARALLEL static engine (RDLB) re-run each batch —
            //                   the decisive baseline ("why not just re-run the parallel miner?").
            if (ExpConfig.s6Datasets.contains(tag)) {
                benchmark("S2-compare", "A-Uniform", "Remine-static", "static",
                        () -> ExpConfig.newRemine(), 1, bA, minUtilRatio, maxRegRatio, oracle);
                benchmark("S2-compare", "A-Uniform", "ParRemine", "rdlb",
                        () -> ExpConfig.newParRemine(cores), cores, bA, minUtilRatio, maxRegRatio, oracle);
            }
        }

        // ---------- S11: the S2 comparison repeated across batch counts ----------
        // S2 compares against the baselines on ONE split of four batches, which is nearly static for
        // a method whose subject is incremental maintenance. S11 repeats it at k=4, 16 and 64, so the
        // comparison is a trend rather than a single point — and on LEVIATHAN the ranking flips as k
        // grows, which a single split cannot show.
        //
        // Remine-static runs at k=4 ONLY: it re-mines the whole database sequentially once per batch,
        // so its cost is linear in k, and it already loses by two orders of magnitude at k=4 (152 s
        // against 1.4 s on BIBLE). Extending it to k=64 costs about six hours and changes nothing.
        if (ExpConfig.runS11BatchCompare && !s1Only && ExpConfig.s6Datasets.contains(tag)) {
            System.out.println("-- S11 baseline comparison across batch counts (best T=" + cores + ") --");
            for (int nb : ExpConfig.S11_BATCH_COUNTS) {
                List<List<List<int[]>>> bK = ExpUtil.split(all, ExpConfig.fineRatios(nb, 0.25));
                String lab = "B=" + nb;
                if (!ExpConfig.s11Mode1Only) {
                    benchmark("S11-batchcompare", lab, "P-RIncHUSP", "trie",
                            () -> ExpConfig.newProposed(cores), cores, bK, minUtilRatio, maxRegRatio, oracle);
                    benchmark("S11-batchcompare", lab, "P-RIncHUSP-seq", "trie",
                            () -> ExpConfig.newProposed(1), 1, bK, minUtilRatio, maxRegRatio, oracle);
                    benchmark("S11-batchcompare", lab, "RIncHusp-Fix0.4", "0.40",
                            () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, bK, minUtilRatio, maxRegRatio, oracle);
                    benchmark("S11-batchcompare", lab, "RIncHusp-Fix0.9", "0.90",
                            () -> ExpConfig.newRIncHusp(ExpConfig.muFixHigh), 1, bK, minUtilRatio, maxRegRatio, oracle);
                }
                // Exact after every batch, so this is the variant that answers at the same correctness
                // as a baseline re-mining per batch. Measured alongside ParRemine in the same session.
                if (ExpConfig.runS11Mode1) {
                    benchmark("S11-batchcompare", lab, "P-RIncHUSP-P", "partition",
                            () -> ExpConfig.newProposedPartition(cores), cores, bK, minUtilRatio, maxRegRatio, oracle);
                }
                benchmark("S11-batchcompare", lab, "ParRemine", "rdlb",
                        () -> ExpConfig.newParRemine(cores), cores, bK, minUtilRatio, maxRegRatio, oracle);
                if (nb == 4 && !ExpConfig.s11Mode1Only) {
                    benchmark("S11-batchcompare", lab, "Remine-static", "static",
                            () -> ExpConfig.newRemine(), 1, bK, minUtilRatio, maxRegRatio, oracle);
                }
            }
        }

        // ---------- S12: one full re-mine of D_new ----------
        // The whole database arrives as a single batch, so initialBuild mines it once and no
        // incremental step runs. This is what an application pays when it needs the answer only at
        // the end of the sequence, and it is therefore the baseline the proposal must beat in that
        // case -- as opposed to the per-batch re-mining that S8 and S11 measure.
        if (ExpConfig.runS12SingleMine && !s1Only && ExpConfig.s6Datasets.contains(tag)) {
            System.out.println("-- S12 single full re-mine of D_new (best T=" + cores + ") --");
            List<List<List<int[]>>> b1 = ExpUtil.split(all, new double[]{1.0});
            benchmark("S12-singlemine", "k=1", "ParRemine", "rdlb",
                    () -> ExpConfig.newParRemine(cores), cores, b1, minUtilRatio, maxRegRatio, oracle);
        }

        // ---------- S4: Robustness across 4 distributions ----------
        if (ExpConfig.runS4Distribution && !s1Only) {
            System.out.println("-- S4 Distribution robustness (best T=" + cores + ") --");
            for (int s = 0; s < ExpConfig.DIST_NAMES.length; s++) {
                String dist = ExpConfig.DIST_NAMES[s];
                List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.DIST_RATIOS[s]);
                benchmark("S4-distribution", dist, "P-RIncHUSP", "trie",
                        () -> ExpConfig.newProposed(cores), cores, b, minUtilRatio, maxRegRatio, oracle);
                benchmark("S4-distribution", dist, "RIncHusp-Fix0.4", "0.40",
                        () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, b, minUtilRatio, maxRegRatio, oracle);
            }
        }

        // ---------- S5: Fine-batch streaming — isolates the maintenance-strategy cost ----------
        // s1Only datasets skip S5 too, EXCEPT the explicit allowlist (SIGN — see ExpConfig.s5ExtraDatasets).
        if (ExpConfig.runS5FineBatch && (!s1Only || ExpConfig.s5ExtraDatasets.contains(tag))) {
            System.out.println("-- S5 Fine-batch streaming (D_old 25% + 15 x 5% increments, best T=" + cores + ") --");
            List<List<List<int[]>>> bF = ExpUtil.split(all, ExpConfig.SCEN_FINE);
            benchmark("S5-finebatch", "F-Fine16", "P-RIncHUSP", "trie",
                    () -> ExpConfig.newProposed(cores), cores, bF, minUtilRatio, maxRegRatio, oracle);
            benchmark("S5-finebatch", "F-Fine16", "P-RIncHUSP-invidx", "invidx",
                    () -> ExpConfig.newProposedInvindex(cores), cores, bF, minUtilRatio, maxRegRatio, oracle);
            benchmark("S5-finebatch", "F-Fine16", "RIncHusp-Fix0.4", "0.40",
                    () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, bF, minUtilRatio, maxRegRatio, oracle);
        }

        // ---------- S6: δ-sensitivity sweep (light datasets only; heavy ones would cost 20h+) ----------
        if (ExpConfig.runS6DeltaSweep && ExpConfig.s6Datasets.contains(tag)) {
            System.out.println("-- S6 δ-sensitivity sweep (distribution A, best T=" + cores + ") --");
            for (double mult : ExpConfig.S6_DELTA_MULT) {
                final double dS = minUtilRatio * mult;
                Set<String> oracleS = oracleOrNull(dS, maxRegRatio);     // recall denominator at THIS δ
                String tick = String.format("d=%.4f", dS);
                benchmark("S6-deltasweep", tick, "P-RIncHUSP", "trie",
                        () -> ExpConfig.newProposed(cores), cores, bA, dS, maxRegRatio, oracleS);
                benchmark("S6-deltasweep", tick, "RIncHusp-Fix0.4", "0.40",
                        () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, bA, dS, maxRegRatio, oracleS);
            }
        }

        // ---------- S7: ρ-sensitivity sweep (light datasets only) ----------
        // The approximate variant is measured HERE on purpose: its recall collapses as ρ tightens
        // (SIGN 0.9667 @ρ=0.30 → 0.7667 @ρ=0.15) because the unsound ρ·N_current seed prune gets
        // proportionally harsher. That collapse is the empirical case for the exact design.
        if (ExpConfig.runS7RhoSweep && ExpConfig.s6Datasets.contains(tag)) {
            System.out.println("-- S7 ρ-sensitivity sweep (distribution A, best T=" + cores + ") --");
            for (double mult : ExpConfig.S7_RHO_MULT) {
                final double rS = maxRegRatio * mult;
                Set<String> oracleS = oracleOrNull(minUtilRatio, rS);   // recall denominator at THIS ρ
                String tick = String.format("r=%.4f", rS);
                benchmark("S7-rhosweep", tick, "P-RIncHUSP", "trie",
                        () -> ExpConfig.newProposed(cores), cores, bA, minUtilRatio, rS, oracleS);
                benchmark("S7-rhosweep", tick, "P-RIncHUSP-approx", "0.40",
                        () -> ExpConfig.newProposedApprox(cores), cores, bA, minUtilRatio, rS, oracleS);
                benchmark("S7-rhosweep", tick, "RIncHusp-Fix0.4", "0.40",
                        () -> ExpConfig.newRIncHusp(ExpConfig.muMin), 1, bA, minUtilRatio, rS, oracleS);
            }
        }

        // ---------- S8: batch-count scaling — THE CROSSOVER (light datasets) ----------
        // The decisive figure: re-mining with the parallel static engine costs O(#updates) while
        // incremental maintenance is flat, so the two curves cross. Both methods are measured over the
        // SAME data split into an increasing number of update cycles.
        if (ExpConfig.runS8BatchScaling && ExpConfig.s6Datasets.contains(tag)) {
            System.out.println("-- S8 batch-count scaling / crossover (best T=" + cores + ") --");
            for (int nb : ExpConfig.S8_BATCH_COUNTS) {
                List<List<List<int[]>>> bN = ExpUtil.split(all, ExpConfig.fineRatios(nb, 0.25));
                benchmark("S8-batchscale", "B=" + nb, "P-RIncHUSP", "trie",
                        () -> ExpConfig.newProposed(cores), cores, bN, minUtilRatio, maxRegRatio, oracle);
                benchmark("S8-batchscale", "B=" + nb, "ParRemine", "rdlb",
                        () -> ExpConfig.newParRemine(cores), cores, bN, minUtilRatio, maxRegRatio, oracle);
            }
        }

        // ---------- S9: θ₀ sweep — why μ=1 is not a tuned constant ----------
        // Two claims, one table. (a) recall is 1.0000 at EVERY μ: exactness does not depend on θ₀, exactly
        // as the partition lemma says. (b) cost moves with μ — the seed gets cheaper as θ₀ rises while
        // discovery gets more expensive — but the SHAPE is dataset-dependent, not a U with its floor at
        // μ=1: the measured minima sit at μ=3 (SIGN), μ=0.4 (LEVIATHAN), μ=1.5 (C8T1) and μ=1 (BIBLE
        // alone). μ=1 is the value the partition lemma makes canonical, each part mined at its own
        // natural threshold (θ₀=δ·U(D_old), θ_disc=δ·U(ΔD)) — not the cheapest one.
        // μ=0.4 is the inherited RIncHusp buffer value.
        if (ExpConfig.runS9MuSweep && ExpConfig.s6Datasets.contains(tag)) {
            System.out.println("-- S9 θ₀ sweep (distribution A, best T=" + cores + ") --");
            for (double mu : ExpConfig.S9_MUS) {
                final double m = mu;
                benchmark("S9-musweep", String.format("mu=%.1f", m), "P-RIncHUSP", String.format("%.2f", m),
                        () -> ExpConfig.newProposedMu(cores, m), cores, bA, minUtilRatio, maxRegRatio, oracle);
            }
        }

        // ---------- S9B: the same λ sweep under the INCREASING distribution ----------
        // Raising μ lifts θ₀ and lowers minUtil−θ₀ by the same amount, so it moves work from seeding
        // into discovery. Under SCEN_A the two phases are comparable; under SCEN_B, D_old is 10% of the
        // data and the increment carries the rest, so discovery dominates and the helpful direction
        // should invert. Opt-in: this is a follow-up probe, not part of the reported suite.
        if (ExpConfig.runS9BMuSweepDistB && ExpConfig.s6Datasets.contains(tag)) {
            List<List<List<int[]>>> bB = ExpUtil.split(all, ExpConfig.SCEN_B);
            System.out.println("-- S9B θ₀ sweep (distribution B-Increasing, best T=" + cores + ") --");
            for (double mu : ExpConfig.S9_MUS) {
                final double m = mu;
                benchmark("S9B-musweep-distB", String.format("mu=%.1f", m), "P-RIncHUSP",
                        String.format("%.2f", m), () -> ExpConfig.newProposedMu(cores, m),
                        cores, bB, minUtilRatio, maxRegRatio, oracle);
            }
        }

        // ---------- S10: exactness ablation — which flag closes which ceiling ----------
        // The seed-once ceiling has TWO independent components needing DIFFERENT bounds:
        //   regBound  (ρ·N_final seed prune) closes the REGULARITY component;
        //   discovery (mine ΔD at minUtil−θ₀) closes the UTILITY component.
        // Neither alone is exact. Both together are. θ₀ is held at its natural value (μ=1) throughout so
        // the 2×2 isolates the FLAGS; the final row adds the pre-fix μ=0.4 buffer to show what it cost.
        if (ExpConfig.runS10Exactness && ExpConfig.s6Datasets.contains(tag)) {
            System.out.println("-- S10 exactness ablation (distribution A, best T=" + cores + ") --");
            final double mu1 = ExpConfig.MU_PARTITION;
            benchmark("S10-exactness", "neither", "P-RIncHUSP[-,-]", "1.00",
                    () -> ExpConfig.newProposedAblation(cores, false, false, mu1), cores, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S10-exactness", "reg-only", "P-RIncHUSP[reg,-]", "1.00",
                    () -> ExpConfig.newProposedAblation(cores, true, false, mu1), cores, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S10-exactness", "disc-only", "P-RIncHUSP[-,disc]", "1.00",
                    () -> ExpConfig.newProposedAblation(cores, false, true, mu1), cores, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S10-exactness", "both-EXACT", "P-RIncHUSP[reg,disc]", "1.00",
                    () -> ExpConfig.newProposedAblation(cores, true, true, mu1), cores, bA, minUtilRatio, maxRegRatio, oracle);
            benchmark("S10-exactness", "pre-fix", "P-RIncHUSP-approx", "0.40",
                    () -> ExpConfig.newProposedApprox(cores), cores, bA, minUtilRatio, maxRegRatio, oracle);
        }
    }

    /** Emit dataset characteristics (once per dataset) to the log AND {@code dataset_stats.csv} — the
     *  paper's "experimental setup" table: #sequences, #distinct items, avg/max length (items),
     *  avg/max #itemsets (events) per sequence, total DB utility. */
    static void writeDatasetStats(String tg, List<List<int[]>> data) throws IOException {
        int nSeq = data.size(), maxLen = 0, maxEvents = 0;
        long totalItems = 0, totalEvents = 0, totalUtil = 0;
        java.util.HashSet<Integer> distinct = new java.util.HashSet<>();
        for (List<int[]> s : data) {
            int items = 0;
            for (int[] ev : s) {
                items += ev.length / 2;
                for (int k = 0; k < ev.length; k += 2) distinct.add(ev[k]);
                for (int k = 1; k < ev.length; k += 2) totalUtil += ev[k];
            }
            totalEvents += s.size(); totalItems += items;
            if (items > maxLen) maxLen = items;
            if (s.size() > maxEvents) maxEvents = s.size();
        }
        double avgLen = nSeq == 0 ? 0 : (double) totalItems / nSeq;
        double avgEvents = nSeq == 0 ? 0 : (double) totalEvents / nSeq;
        System.out.printf("   [stats %s] N=%d distinctItems=%d avgLen=%.1f maxLen=%d avgEvents=%.1f maxEvents=%d totalUtil=%d%n",
                tg, nSeq, distinct.size(), avgLen, maxLen, avgEvents, maxEvents, totalUtil);
        if (ctx != null) ctx.appendDatasetStats(tg, String.format("%s,%d,%d,%.2f,%d,%.2f,%d,%d",
                tg, nSeq, distinct.size(), avgLen, maxLen, avgEvents, maxEvents, totalUtil));
    }

    /** Oracle = RHusp remining the full DB (recall denominator). null if N>coverageMaxN or OOM/timeout. */
    static Set<String> oracleOrNull(double d, double r) {
        if (all.size() > ExpConfig.coverageMaxN) return null;
        ExecutorService ex = Executors.newSingleThreadExecutor(daemon());
        // In absolute mode the oracle mines at the DECLARED (scaled) B: the bound defines the
        // problem, and an oracle bound derived from the ratio would answer a different question
        // than the miners were asked -- off by one after truncation, hidden by recall's direction.
        final int oracleAbsB = scaledAbsB(r);
        Future<Set<String>> f = ex.submit(() -> oracleAbsB > 0
                ? ExpUtil.oracleCanon(all, d, oracleAbsB)
                : ExpUtil.oracleCanon(all, d, r));
        try { return f.get(ExpConfig.oracleTimeoutMs, TimeUnit.MILLISECONDS); }
        catch (Throwable t) { f.cancel(true); return null; }
        finally { ex.shutdownNow(); }
    }

    /** Resume-aware wrapper: skip a cell already finished in this run (reusing its aggregate so the S1
     *  speed-up table stays correct); otherwise run it and record completion in {@code completed.txt}. */
    static Agg benchmark(String scenario, String dist, String algo, String mu, Supplier<IncrementalHUSPMiner> factory,
                         int threads, List<List<List<int[]>>> b, double d, double r, Set<String> oracle) throws IOException {
        // One choke point for the absolute bound: the DECLARED per-dataset constant, scaled by the
        // same multiplier a sweep applies to rho so swept cells stay coherent. Declared numbers only;
        // the database size is never consulted -- deriving B from it would smuggle back the final-size
        // knowledge the absolute formulation exists to remove.
        ExpConfig.absoluteB = scaledAbsB(r);
        String cell = tag + "|" + scenario + "|" + dist + "|" + algo + "|" + threads;
        if (ctx != null && ctx.isCellDone(cell)) {
            long[] pa = ctx.priorAgg(cell);
            boolean ok = pa != null && pa[0] == 1;
            System.out.printf("   [%-15s %-15s T=%-2d] SKIP (done in prior run)%s%n", scenario, algo, threads,
                    ok ? "  median=" + pa[1] + "ms HS=" + pa[2] : "");
            return ok ? new Agg(true, pa[1], (int) pa[2], (int) pa[3]) : Agg.failed();
        }
        Agg a = benchmarkImpl(scenario, dist, algo, mu, factory, threads, b, d, r, oracle);
        if (ctx != null) ctx.recordCell(cell);
        return a;
    }

    /** Warm-up, then measure a tiered number of times (see ExpConfig.repeatsForRuntime); write 1 row
     *  per run; return aggregate (median, hs, shs, recall). */
    static Agg benchmarkImpl(String scenario, String dist, String algo, String mu, Supplier<IncrementalHUSPMiner> factory,
                         int threads, List<List<List<int[]>>> b, double d, double r, Set<String> oracle) throws IOException {
        Run warm = null;
        for (int w = 0; w < Math.max(1, ExpConfig.warmupRuns); w++) warm = timedRun(factory, b, d, r);
        if (warm.timedOut || warm.error != null || warm.patterns == null) {
            writeRow(scenario, dist, algo, mu, d, r, threads, b.size(), 0, warm, "", -1, -1);
            System.out.printf("   [%-15s %-10s %-15s T=%-2d] %s%n", scenario, dist, algo, threads,
                    warm.timedOut ? "TIMEOUT" : "ERROR:" + warm.error);
            return Agg.failed();
        }
        String recall = oracle != null ? String.format("%.4f", ExpUtil.coverage(warm.patterns, oracle)) : "";
        // Recall is blind to spurious patterns: a result containing every oracle pattern scores 1.0
        // no matter how much it also returns that it should not. The absolute-mode runs surfaced
        // exactly that -- the sequential baseline returning supersets with recall 1.0 -- so the row
        // now carries the oracle's size and the hit count, from which precision follows. hs_count
        // alone cannot expose a superset, and a table that prints recall without precision beside a
        // larger hs_count is telling half the truth.
        int oracleSize = oracle != null ? oracle.size() : -1;
        int oracleHits = oracle != null ? ExpUtil.hits(warm.patterns, oracle) : -1;
        // Repeat count is tiered by how long a run takes, not fixed. A sub-second run needs many
        // repeats before its mean is trustworthy; a two-minute run needs few, and forcing many would
        // dominate the suite. The warm-up run just measured the duration, so use it to pick the tier.
        // The mean and standard deviation the paper reports are computed downstream from the per-run
        // rows written here, so they stay correct whatever count each configuration gets.
        int repeats = ExpConfig.repeatsForRuntime(warm.runtimeMs);
        long[] times = new long[repeats]; int n = 0; Run last = warm;
        for (int it = 1; it <= repeats; it++) {
            System.gc();
            Run rr = timedRun(factory, b, d, r);
            writeRow(scenario, dist, algo, mu, d, r, threads, b.size(), it, rr, recall, oracleSize, oracleHits);
            last = rr;
            if (rr.timedOut) break;
            times[n++] = rr.runtimeMs;
        }
        long median = n > 0 ? median(Arrays.copyOf(times, n)) : -1;
        // Include dist so S8/S11 rows show which batch count (B=4/B=64) and S7 which rho (r=...);
        // otherwise the console shows several identical-looking lines that differ only in the CSV.
        System.out.printf("   [%-15s %-10s %-15s T=%-2d] %6d ms | %7.1f MB | HS=%d SHS=%d%s%n",
                scenario, dist, algo, threads, median, last.peakMb, last.count, last.shs,
                recall.isEmpty() ? "" : " | recall=" + recall);
        return new Agg(true, median, last.count, last.shs);
    }

    static Run timedRun(Supplier<IncrementalHUSPMiner> factory, List<List<List<int[]>>> b, double d, double r) {
        ExecutorService ex = Executors.newSingleThreadExecutor(daemon());
        IncrementalHUSPMiner m = factory.get();
        Callable<Run> task = () -> {
            long[] phase = new long[3];
            double[] phaseMem = new double[3];
            int[] held = new int[2];
            // One meter for every miner (see PeakMemoryMeter). Read the peak from it, not from the
            // miner, so the peak-memory column compares like with like.
            PeakMemoryMeter meter = new PeakMemoryMeter();
            long t0 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(m, b, d, r, phase, meter, phaseMem, held);
            Run rr = new Run();
            rr.runtimeMs = System.currentTimeMillis() - t0;
            rr.buildMs = phase[0]; rr.incrMs = phase[1]; rr.discMs = phase[2];
            rr.seedMb = phaseMem[0]; rr.incrMb = phaseMem[1]; rr.discMb = phaseMem[2];
            rr.peakMb = meter.peakMB();
            meter.close();
            rr.count = res.size();
            rr.shs = m.bufferedCount();
            rr.trackedSeed = held[0]; rr.tracked = held[1];
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
                         int threads, int nb, int iter, Run run, String recall,
                         int oracleSize, int oracleHits) throws IOException {
        String status = run.timedOut ? "TIMEOUT" : (run.error != null ? "ERROR" : "OK");
        csv.write(String.format("%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%s,%s,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d%n",
                tag, scenario, dist, algo, mu, d, r, threads, nb, iter,
                run.timedOut ? -1 : run.runtimeMs,
                run.timedOut ? -1 : run.buildMs,
                run.timedOut ? -1 : run.incrMs,
                run.timedOut ? -1 : run.discMs,
                run.timedOut ? 0.0 : run.peakMb,
                run.timedOut ? -1 : run.count,
                run.timedOut ? -1 : run.shs,
                recall, status,
                run.timedOut ? 0.0 : run.seedMb,
                run.timedOut ? 0.0 : run.incrMb,
                run.timedOut ? 0.0 : run.discMb,
                run.timedOut ? -1 : run.trackedSeed,
                run.timedOut ? -1 : run.tracked,
                oracleSize, oracleHits,
                ExpConfig.absoluteB));
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
        long runtimeMs; long buildMs; long incrMs; long discMs; double peakMb; int count; int shs;
        double seedMb; double incrMb; double discMb;   // peak reached DURING each phase; see PeakMemoryMeter
        int trackedSeed; int tracked;                  // patterns HELD after seeding / at the end
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
