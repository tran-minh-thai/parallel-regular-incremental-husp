package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoParRemine;
import algorithms.AlgoRHUSP;
import algorithms.AlgoRHUSPMinerParallel;
import algorithms.IncrementalHUSPMiner;
import algorithms.SeqConverter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feasibility probe for exact answers under a query schedule.
 *
 * <p>The data arrive as {@code k} batches (the first holds a quarter of the data, the rest split
 * evenly). After the batches named by the schedule, each miner is queried, and its answer is compared
 * with an independent oracle ({@link AlgoRHUSP}, a different code path from the parallel engine)
 * mined on exactly the same prefix: pattern keys, utilities and maximum periods, cell by cell. Miners:
 * <ul>
 *   <li>{@code keep}: the proposed miner, anchor fixed at seeding (the reported behaviour);</li>
 *   <li>{@code reset}: the proposed miner, anchor moved to the current prefix after every query;</li>
 *   <li>suffix {@code -sfx} on either: discovery also prunes on the leading-gap lower bound of the
 *       suffix window ({@link AlgoPRIncHUSP#suffixWindowPrune}); suffix {@code -gf}: fresh candidates
 *       whose item posting lists leave a gap above the bound are rejected before the state rebuild
 *       ({@link AlgoPRIncHUSP#rebuildGapFilter});</li>
 *   <li>{@code remine}: the parallel static engine run from scratch at each query only.</li>
 * </ul>
 * All three use the same engine scheduler, so timing differences are not a scheduler artefact.
 *
 * <pre>
 *   java -cp out test.QueryScheduleProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 21 16 every
 *   java -cp out test.QueryScheduleProbe ... 16 every:4 keep,reset
 *   java -Dfault=2 -cp out test.QueryScheduleProbe ...      # deliberate defect; the probe must fail
 * </pre>
 *
 * <p>Output goes to {@code results-probe/<timestamp>_<dataset>/}: {@code run_meta.json} (command,
 * commit, tree state, source and data hashes, environment), {@code exactness.csv} (counts only) and
 * {@code timing_feasibility.csv}. The timings come from the development machine with no warm-up
 * and no repetitions: they decide whether a mechanism is worth building, nothing more.
 * Exit status is non-zero when any query disagrees with the oracle or no query could be compared.
 */
public class QueryScheduleProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: QueryScheduleProbe seq eui delta absB batches schedule [policies]");
            System.exit(2);
        }
        String seqFile = args[0], euiFile = args[1];
        double delta = Double.parseDouble(args[2]);
        int absB = Integer.parseInt(args[3]);
        int k = Integer.parseInt(args[4]);
        String schedule = args[5];
        String[] policies = (args.length > 6 ? args[6] : "keep-sfx,reset-sfx,remine").split(",");
        int threads = Integer.getInteger("threads", Runtime.getRuntime().availableProcessors());
        AlgoPRIncHUSP.FAULT = Integer.getInteger("fault", 0);
        // Declare the bound before any miner is built: ExpConfig switches on the floor prune and the
        // permanent eviction only under a declared absolute bound, as in the reported runs.
        ExpConfig.absoluteMode = true;
        ExpConfig.absoluteB = absB;
        String tag = ExpUtil.datasetTag(seqFile);

        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        double dOld = Double.parseDouble(System.getProperty("dold", "0.25"));
        boolean remineReference = "remine".equals(System.getProperty("oracle"));
        List<List<List<int[]>>> batches = ExpUtil.split(all, ExpConfig.fineRatios(k, dOld));
        boolean[] queryAfter = schedule(schedule, batches.size());

        File dir = new File("results-probe",
                new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "_" + tag
                        + (AlgoPRIncHUSP.FAULT != 0 ? "_fault" + AlgoPRIncHUSP.FAULT : ""));
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
        writeMeta(dir, args, seqFile, euiFile, threads);
        System.out.printf("%s N=%d delta=%s B=%d k=%d dOld=%s schedule=%s threads=%d fault=%d reference=%s -> %s%n",
                tag, all.size(), args[2], absB, batches.size(), dOld, schedule, threads, AlgoPRIncHUSP.FAULT,
                remineReference ? "parallel engine (NOT independent)" : "independent oracle", dir);

        // Oracle per queried prefix, computed once and shared by every policy.
        Map<Integer, Map<String, long[]>> oracle = new HashMap<>();
        List<List<int[]>> prefix = new ArrayList<>();
        for (int b = 0; b < batches.size(); b++) {
            prefix.addAll(batches.get(b));
            if (!queryAfter[b]) continue;
            long minUtil = (long) Math.ceil(delta * ExpUtil.totalUtil(prefix));
            Map<String, long[]> canon = new HashMap<>();
            if (remineReference) {
                // Reference = the parallel engine re-run on the prefix. NOT independent of the proposal's
                // discovery engine; used only where the independent oracle is too slow (hours per prefix).
                IncrementalHUSPMiner ref = build("remine", threads, absB);
                ref.initialBuild(new ArrayList<>(prefix), delta, 0.0);
                for (Map.Entry<String, long[]> e : ref.getHighUtilityPatterns().entrySet())
                    canon.put(SeqConverter.canonical(e.getKey()), e.getValue());
                ref.close();
            } else {
                AlgoRHUSP o = new AlgoRHUSP();
                for (Map.Entry<String, long[]> e : o.mine(new ArrayList<>(prefix), minUtil, absB).entrySet())
                    canon.put(SeqConverter.canonical(e.getKey()), e.getValue());
            }
            oracle.put(b, canon);
            System.out.printf("  oracle after batch %d: N=%d minUtil=%d patterns=%d%n",
                    b, prefix.size(), minUtil, canon.size());
        }

        int compared = 0, failing = 0;
        try (BufferedWriter ex = new BufferedWriter(new FileWriter(new File(dir, "exactness.csv")));
             BufferedWriter tm = new BufferedWriter(new FileWriter(new File(dir, "timing_feasibility.csv")))) {
            ex.write("dataset,policy,batches,schedule,fault,query,after_batch,n_seqs,oracle_size,returned,"
                    + "missing,extra,utility_mismatch,period_mismatch,increment_seqs,threshold,candidates,fresh,"
                    + "gap_rejected,tracked_after,action,r\n");
            tm.write("dataset,policy,batches,schedule,fault,query,after_batch,ingest_ms,query_ms,"
                    + "discovery_mine_ms,rebuild_ms,seed_ms\n");
            for (String policy : policies) {
                IncrementalHUSPMiner m = build(policy, threads, absB);
                long t0 = System.nanoTime();
                m.initialBuild(batches.get(0), delta, 0.0);
                long seedMs = (System.nanoTime() - t0) / 1_000_000;
                long ingestMs = 0;
                int n = batches.get(0).size(), q = 0;
                for (int b = 0; b < batches.size(); b++) {
                    if (b > 0) {
                        long t1 = System.nanoTime();
                        m.processBatch(batches.get(b));
                        ingestMs += (System.nanoTime() - t1) / 1_000_000;
                        n += batches.get(b).size();
                    }
                    if (!queryAfter[b]) continue;
                    long t2 = System.nanoTime();
                    Map<String, long[]> res = new HashMap<>(m.getHighUtilityPatterns());
                    long queryMs = (System.nanoTime() - t2) / 1_000_000;
                    int[] d = diff(res, oracle.get(b));
                    compared++;
                    if (d[0] + d[1] + d[2] + d[3] > 0) failing++;
                    AlgoPRIncHUSP p = (m instanceof AlgoPRIncHUSP) ? (AlgoPRIncHUSP) m : null;
                    ex.write(String.join(",", tag, policy, "" + batches.size(), schedule, "" + AlgoPRIncHUSP.FAULT,
                            "" + q, "" + b, "" + n, "" + oracle.get(b).size(), "" + res.size(),
                            "" + d[0], "" + d[1], "" + d[2], "" + d[3],
                            p == null ? "" : "" + p.lastQueryIncrementSeqs,
                            p == null ? "" : "" + p.lastQueryThreshold,
                            p == null ? "" : "" + p.lastQueryCandidates,
                            p == null ? "" : "" + p.lastQueryFresh,
                            p == null ? "" : "" + p.lastQueryGapRejected,
                            p == null ? "" : "" + m.trackedCount(),
                            p == null || !p.adaptiveQuery ? "" : (p.lastQueryAction == 0 ? "discover" : p.lastQueryAction == 1 ? "remine" : "none"),
                            p == null || !p.adaptiveQuery ? "" : String.format(java.util.Locale.ROOT, "%.2f", p.lastQueryR)) + "\n");
                    tm.write(String.join(",", tag, policy, "" + batches.size(), schedule, "" + AlgoPRIncHUSP.FAULT,
                            "" + q, "" + b, "" + ingestMs, "" + queryMs,
                            p == null ? "" : "" + p.lastQueryMineMs,
                            p == null ? "" : "" + p.lastQueryRebuildMs,
                            q == 0 ? "" + seedMs : "") + "\n");
                    System.out.printf("  %-6s q%-2d after b%-2d N=%-6d oracle=%-6d got=%-6d miss=%d extra=%d "
                                    + "uErr=%d pErr=%d  ingest=%dms query=%dms%s%n",
                            policy, q, b, n, oracle.get(b).size(), res.size(), d[0], d[1], d[2], d[3],
                            ingestMs, queryMs, p == null ? ""
                                    : String.format(java.util.Locale.ROOT, "  [%sthr=%d cand=%d fresh=%d gapRej=%d mine=%dms rebuild=%dms tracked=%d]",
                                    p.adaptiveQuery ? (p.lastQueryAction == 0 ? "DISCOVER r=" : "REMINE r=")
                                            + String.format(java.util.Locale.ROOT, "%.1f ", p.lastQueryR) : "",
                                    p.lastQueryThreshold, p.lastQueryCandidates, p.lastQueryFresh, p.lastQueryGapRejected,
                                    p.lastQueryMineMs, p.lastQueryRebuildMs, m.trackedCount()));
                    ingestMs = 0;
                    q++;
                }
                ex.flush(); tm.flush();
                m.close();
            }
        }
        System.out.printf("compared queries=%d failing=%d%n", compared, failing);
        if (compared == 0 || failing > 0) System.exit(1);
    }

    static IncrementalHUSPMiner build(String policy, int threads, int absB) {
        IncrementalHUSPMiner m;
        switch (policy) {
            case "remine": {
                AlgoParRemine r = ExpConfig.newParRemine(threads);
                r.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;   // same scheduler as the proposal
                r.mineOnQuery = true;
                m = r;
                break;
            }
            default: {
                // keep|reset, optionally followed by -sfx (suffix-window prune), -gf (rebuild gap filter)
                // and -l<lambda> (anchor threshold = lambda * minUtil at seeding; default 1)
                // sel[-k<kappa>]: the adaptive selector (keep anchor, suffix prune, gap filter, lambda 1)
                List<String> parts = new ArrayList<>(java.util.Arrays.asList(policy.split("-")));
                boolean sel = parts.get(0).equals("sel");
                double kappa = 50.0;
                for (String s : parts) if (s.startsWith("k") && !s.equals("keep")) kappa = Double.parseDouble(s.substring(1));
                if (sel) { parts.set(0, "keep"); parts.add("sfx"); parts.add("gf"); }
                if (!parts.get(0).equals("keep") && !parts.get(0).equals("reset"))
                    throw new IllegalArgumentException("unknown policy " + policy);
                double lambda = 1.0;
                for (String s : parts) if (s.startsWith("l")) lambda = Double.parseDouble(s.substring(1));
                if (lambda != 1.0 && parts.get(0).equals("reset"))
                    throw new IllegalArgumentException("anchor reset is defined for lambda = 1 only");
                AlgoPRIncHUSP p = lambda == 1.0 ? ExpConfig.newProposed(threads) : ExpConfig.newProposedMu(threads, lambda);
                p.integerDiscoveryThreshold = true;
                p.anchorPolicy = parts.get(0).equals("reset") ? AlgoPRIncHUSP.ANCHOR_RESET : AlgoPRIncHUSP.ANCHOR_KEEP;
                p.suffixWindowPrune = parts.contains("sfx");
                p.rebuildGapFilter = parts.contains("gf");
                p.adaptiveQuery = sel;
                p.selectorKappa = kappa;
                m = p;
            }
        }
        m.setAbsoluteMaxReg(absB);
        return m;
    }

    /** Batches after which a query is issued: "every", "every:N" (every N-th batch and the last), "final". */
    static boolean[] schedule(String spec, int k) {
        boolean[] q = new boolean[k];
        if (spec.equals("final")) q[k - 1] = true;
        else if (spec.equals("every")) for (int b = 1; b < k; b++) q[b] = true;
        else if (spec.startsWith("every:")) {
            int step = Integer.parseInt(spec.substring(6));
            for (int b = step; b < k; b += step) q[b] = true;
            q[k - 1] = true;
        } else throw new IllegalArgumentException("unknown schedule " + spec);
        return q;
    }

    /** {missing, extra, utility mismatches, period mismatches} of {@code res} against the oracle. */
    static int[] diff(Map<String, long[]> res, Map<String, long[]> oracle) {
        Map<String, long[]> got = new HashMap<>();
        for (Map.Entry<String, long[]> e : res.entrySet()) got.put(SeqConverter.canonical(e.getKey()), e.getValue());
        int missing = 0, extra = 0, uErr = 0, pErr = 0;
        for (Map.Entry<String, long[]> e : oracle.entrySet()) {
            long[] g = got.get(e.getKey());
            if (g == null) { missing++; continue; }
            if (g[0] != e.getValue()[0]) uErr++;
            if (g.length > 1 && g[1] != e.getValue()[1]) pErr++;
        }
        for (String key : got.keySet()) if (!oracle.containsKey(key)) extra++;
        return new int[]{missing, extra, uErr, pErr};
    }

    private static void writeMeta(File dir, String[] args, String seqFile, String euiFile, int threads)
            throws Exception {
        StringBuilder props = new StringBuilder();
        for (String key : new String[]{"fault", "threads", "dold", "oracle"})
            if (System.getProperty(key) != null) props.append("-D").append(key).append('=').append(System.getProperty(key)).append(' ');
        String src = "src/main/java/";
        StringBuilder sb = new StringBuilder("{\n");
        sb.append(kv("kind", "probe (development machine, feasibility only)")).append(",\n");
        sb.append(kv("command", ("java " + props + "test.QueryScheduleProbe " + String.join(" ", args)).trim())).append(",\n");
        sb.append(kv("git_commit", git("rev-parse", "HEAD"))).append(",\n");
        String dirty = git("status", "--porcelain", "--", "src");
        sb.append(kv("tree", dirty.isEmpty() ? "clean" : "MODIFIED (" + dirty.split("\n").length + " files)")).append(",\n");
        sb.append(kv("sha256_AlgoPRIncHUSP", sha(src + "algorithms/AlgoPRIncHUSP.java"))).append(",\n");
        sb.append(kv("sha256_AlgoParRemine", sha(src + "algorithms/AlgoParRemine.java"))).append(",\n");
        sb.append(kv("sha256_AlgoRHUSPMinerParallel", sha(src + "algorithms/AlgoRHUSPMinerParallel.java"))).append(",\n");
        sb.append(kv("sha256_QueryScheduleProbe", sha(src + "test/QueryScheduleProbe.java"))).append(",\n");
        sb.append(kv("sha256_seq", sha(seqFile))).append(",\n");
        sb.append(kv("sha256_eui", sha(euiFile))).append(",\n");
        sb.append(kv("java", System.getProperty("java.runtime.version") + " " + System.getProperty("java.vendor"))).append(",\n");
        sb.append(kv("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"))).append(",\n");
        sb.append(kv("threads", "" + threads)).append(",\n");
        sb.append(kv("max_heap_mb", "" + Runtime.getRuntime().maxMemory() / (1024 * 1024))).append(",\n");
        sb.append(kv("started", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()))).append("\n}\n");
        Files.write(new File(dir, "run_meta.json").toPath(), sb.toString().getBytes("UTF-8"));
    }

    private static String kv(String k, String v) {
        return "  \"" + k + "\": \"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String git(String... a) {
        try {
            String[] cmd = new String[a.length + 1];
            cmd[0] = "git";
            System.arraycopy(a, 0, cmd, 1, a.length);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), "UTF-8").trim();
                return p.waitFor() == 0 ? out : "unavailable";
            }
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String sha(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(Files.readAllBytes(new File(path).toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
