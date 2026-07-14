package test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * <h1>Provenance + crash-resume for the official experiment</h1>
 *
 * Everything for ONE run lives in a self-describing directory {@code results/run_<timestamp>_<confighash>/}:
 * <ul>
 *   <li><b>meta.properties</b> — environment (OS / JVM / cores / max heap / host / git commit) + the
 *       config that produced the numbers (δ,ρ,s1Only per dataset; μ band; warm-up/measured runs;
 *       per-run timeout; scenario switches) + {@code config.signature} + {@code status}.</li>
 *   <li><b>results.csv</b> — one row per (dataset, scenario, distribution, algorithm, threads, iteration).</li>
 *   <li><b>completed.txt</b> — one line per FINISHED benchmark cell (append + flush): the resume authority.</li>
 *   <li><b>datasets_done.txt</b> — one line per dataset whose S1–S4 all finished (lets resume skip the
 *       expensive re-load + oracle of a fully-done dataset).</li>
 *   <li><b>DONE</b> — empty marker written IFF the whole suite finished. Its ABSENCE ⇒ aborted/partial.</li>
 * </ul>
 *
 * <h3>Which results are valid / which are stale</h3>
 * A run directory is a <b>valid, complete</b> result set IFF it contains a {@code DONE} file. Two runs
 * are the <b>same experiment</b> IFF their {@code config.signature} match; a directory whose signature
 * differs from the current config is from an older/other configuration and is <b>stale</b> for it.
 *
 * <h3>Resume</h3>
 * With {@code --resume}, the newest run dir whose signature matches and that has NO {@code DONE} is
 * reused: cells in {@code completed.txt} are skipped (their aggregate is reloaded from {@code results.csv}
 * so the S1 speed-up table stays correct), fully-done datasets are skipped whole, and only the missing
 * work runs — appending to the same files.
 */
public final class RunContext {

    public final File dir;
    public final BufferedWriter csv;         // results.csv (append on resume)
    public final boolean resumed;

    private final Set<String> completed = new HashSet<>();      // cell keys already finished
    private final Set<String> datasetsDone = new HashSet<>();   // dataset tags fully finished
    private final Map<String, long[]> priorAgg = new HashMap<>(); // cell -> {ok(1/0), medianMs, hs, shs}
    private final BufferedWriter completedW;
    private final BufferedWriter datasetsW;
    private final File doneMarker;
    private final File metaFile;
    private final Properties meta = new Properties();

    private RunContext(File dir, boolean resumed) throws IOException {
        this.dir = dir;
        this.resumed = resumed;
        this.doneMarker = new File(dir, "DONE");
        this.metaFile = new File(dir, "meta.properties");
        File csvFile = new File(dir, "results.csv");
        boolean csvExists = csvFile.exists() && csvFile.length() > 0;
        if (resumed) { loadProgress(csvFile); if (csvExists) pruneCsvToCompleted(csvFile); }
        this.csv = new BufferedWriter(new FileWriter(csvFile, resumed && csvExists));
        if (!(resumed && csvExists)) { csv.write(ExperimentOfficial.HEADER); csv.flush(); }
        this.completedW = new BufferedWriter(new FileWriter(new File(dir, "completed.txt"), true));
        this.datasetsW  = new BufferedWriter(new FileWriter(new File(dir, "datasets_done.txt"), true));
    }

    /** Create a fresh run dir, or (resume) reuse the newest matching, non-DONE dir. */
    public static RunContext start(List<DatasetSpec> suite, boolean resume) throws IOException {
        String sig = signature(suite);
        String sig8 = Integer.toHexString(sig.hashCode());
        new File("results").mkdirs();

        File dir = null;
        boolean resumed = false;
        if (resume) {
            File best = null; long bestT = -1;
            File[] runs = new File("results").listFiles(f -> f.isDirectory() && f.getName().startsWith("run_"));
            if (runs != null) for (File f : runs) {
                if (new File(f, "DONE").exists()) continue;                       // already complete
                Properties p = new Properties();
                try (Reader r = new FileReader(new File(f, "meta.properties"))) { p.load(r); }
                catch (IOException e) { continue; }
                if (!sig8.equals(p.getProperty("config.signature8"))) continue;   // different config
                if (f.lastModified() > bestT) { bestT = f.lastModified(); best = f; }
            }
            if (best != null) { dir = best; resumed = true; }
        }
        if (dir == null) {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            dir = new File("results", "run_" + stamp + "_" + sig8);
            dir.mkdirs();
        }
        RunContext ctx = new RunContext(dir, resumed);
        ctx.writeMeta(suite, sig, sig8);
        return ctx;
    }

    // ---- resume queries / recording ----
    /** cell = tag|scenario|distribution|algorithm|threads. */
    public boolean isCellDone(String cell)  { return completed.contains(cell); }
    public long[]  priorAgg(String cell)    { return priorAgg.get(cell); }
    public boolean isDatasetDone(String tag){ return datasetsDone.contains(tag); }

    public void recordCell(String cell) throws IOException {
        if (completed.add(cell)) { completedW.write(cell); completedW.write("\n"); completedW.flush(); }
    }
    public void recordDataset(String tag) throws IOException {
        if (datasetsDone.add(tag)) { datasetsW.write(tag); datasetsW.write("\n"); datasetsW.flush(); }
    }

    private BufferedWriter statsW;
    private final Set<String> statsWritten = new HashSet<>();
    /** Append one dataset-characteristics row to {@code dataset_stats.csv} (header written once; deduped by tag). */
    public void appendDatasetStats(String tag, String line) throws IOException {
        if (!statsWritten.add(tag)) return;
        if (statsW == null) {
            File f = new File(dir, "dataset_stats.csv");
            boolean existed = f.exists() && f.length() > 0;
            statsW = new BufferedWriter(new FileWriter(f, true));
            if (!existed) statsW.write("dataset,n_sequences,distinct_items,avg_len_items,max_len_items,avg_events,max_events,total_utility\n");
        }
        statsW.write(line); statsW.write("\n"); statsW.flush();
    }

    /** Mark the whole suite complete: write DONE + meta status. */
    public void finish() throws IOException {
        meta.setProperty("status", "completed");
        meta.setProperty("endTime", new java.util.Date().toString());
        saveMeta();
        try (Writer w = new FileWriter(doneMarker)) { w.write("completed " + new java.util.Date() + System.lineSeparator()); }
    }

    public void close() {
        try { csv.flush(); csv.close(); } catch (IOException ignore) {}
        try { completedW.close(); } catch (IOException ignore) {}
        try { datasetsW.close(); } catch (IOException ignore) {}
        try { if (statsW != null) statsW.close(); } catch (IOException ignore) {}
    }

    // ---- internals ----
    private void writeMeta(List<DatasetSpec> suite, String sig, String sig8) throws IOException {
        if (metaFile.exists()) try (Reader r = new FileReader(metaFile)) { meta.load(r); } catch (IOException ignore) {}
        if (meta.getProperty("startTime") == null) {
            meta.setProperty("startTimeMillis", Long.toString(System.currentTimeMillis()));
            meta.setProperty("startTime", new java.util.Date().toString());
        }
        meta.setProperty("status", resumed ? "resumed-running" : "running");
        meta.setProperty("config.signature8", sig8);
        meta.setProperty("config.signature", sig);
        Runtime rt = Runtime.getRuntime();
        meta.setProperty("env.os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        meta.setProperty("env.java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        meta.setProperty("env.cores", Integer.toString(rt.availableProcessors()));
        meta.setProperty("env.maxHeapMB", Long.toString(rt.maxMemory() / (1024 * 1024)));
        meta.setProperty("env.user", System.getProperty("user.name", "?"));
        try { meta.setProperty("env.host", java.net.InetAddress.getLocalHost().getHostName()); } catch (Exception ignore) {}
        meta.setProperty("env.gitCommit", gitCommit());
        meta.setProperty("config.warmupRuns", Integer.toString(ExpConfig.warmupRuns));
        meta.setProperty("config.measuredRuns", Integer.toString(ExpConfig.measuredRuns));
        meta.setProperty("config.runTimeoutMs", Long.toString(ExpConfig.runTimeoutMs));
        meta.setProperty("config.muMin", Double.toString(ExpConfig.muMin));
        meta.setProperty("config.muMax", Double.toString(ExpConfig.muMax));
        meta.setProperty("config.coverageMaxN", Integer.toString(ExpConfig.coverageMaxN));
        meta.setProperty("config.runS1", Boolean.toString(ExpConfig.runS1Scalability));
        meta.setProperty("config.runS2", Boolean.toString(ExpConfig.runS2Compare));
        meta.setProperty("config.runS4", Boolean.toString(ExpConfig.runS4Distribution));
        meta.setProperty("config.runS5", Boolean.toString(ExpConfig.runS5FineBatch));
        meta.setProperty("config.runS6", Boolean.toString(ExpConfig.runS6DeltaSweep));
        meta.setProperty("config.s6DeltaMult", java.util.Arrays.toString(ExpConfig.S6_DELTA_MULT));
        StringBuilder sb = new StringBuilder();
        for (DatasetSpec s : suite)
            sb.append(s.tag).append("(d=").append(s.minUtilRatio).append(",r=").append(s.maxRegRatio)
              .append(s.s1Only ? ",S1only" : "").append(") ");
        meta.setProperty("config.suite", sb.toString().trim());
        saveMeta();
    }

    private void saveMeta() throws IOException {
        try (Writer w = new FileWriter(metaFile)) { meta.store(w, "P-RIncHUSP experiment run — provenance + resume state"); }
    }

    /** Load completed.txt / datasets_done.txt, and reconstruct per-cell aggregates from results.csv. */
    private void loadProgress(File csvFile) {
        readLines(new File(dir, "completed.txt"), completed);
        readLines(new File(dir, "datasets_done.txt"), datasetsDone);
        if (!csvFile.exists()) return;
        Map<String, List<Long>> times = new HashMap<>();
        Map<String, long[]> lastHsShs = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader(csvFile))) {
            r.readLine(); // header
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length < 16) continue;
                String cell = c[0] + "|" + c[1] + "|" + c[2] + "|" + c[3] + "|" + c[7];
                if (!completed.contains(cell)) continue;
                try {
                    long rtMs = Long.parseLong(c[10].trim());
                    int hs = Integer.parseInt(c[12].trim());
                    int shs = Integer.parseInt(c[13].trim());
                    if ("OK".equals(c[15].trim()) && rtMs >= 0)
                        times.computeIfAbsent(cell, k -> new ArrayList<>()).add(rtMs);
                    lastHsShs.put(cell, new long[]{hs, shs});
                } catch (NumberFormatException ignore) {}
            }
        } catch (IOException ignore) {}
        for (String cell : completed) {
            List<Long> ts = times.get(cell);
            long[] hsShs = lastHsShs.getOrDefault(cell, new long[]{-1, -1});
            if (ts != null && !ts.isEmpty()) {
                Collections.sort(ts);
                priorAgg.put(cell, new long[]{1, ts.get(ts.size() / 2), hsShs[0], hsShs[1]});
            } else {
                priorAgg.put(cell, new long[]{0, -1, hsShs[0], hsShs[1]});
            }
        }
    }

    /** Rewrite results.csv keeping ONLY rows of completed cells — drops partial rows from a crashed cell
     *  and any duplicate rows a redo would create, so results.csv always holds exactly the valid results. */
    private void pruneCsvToCompleted(File csvFile) throws IOException {
        File tmp = new File(dir, "results.csv.tmp");
        try (BufferedReader r = new BufferedReader(new FileReader(csvFile));
             BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
            String header = r.readLine();
            w.write(header != null ? header : ExperimentOfficial.HEADER.trim()); w.write("\n");
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length < 16) continue;
                String cell = c[0] + "|" + c[1] + "|" + c[2] + "|" + c[3] + "|" + c[7];
                if (completed.contains(cell)) { w.write(line); w.write("\n"); }
            }
        }
        Files.move(tmp.toPath(), csvFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void readLines(File f, Set<String> into) {
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) into.add(line); }
        } catch (IOException ignore) {}
    }

    /** Signature of every knob that changes the NUMBERS — bump the prefix if the schema changes.
     *  v3: proposed maintain = content-driven parallel trie (lazy retired); S5 ablation = trie vs
     *  inverted-index.
     *  v5: the proposed miner is now EXACT (sound ρ·N_final seed prune + ΔD discovery at minUtil−θ₀,
     *  θ₀ at its natural value μ=1). Adds S9 (θ₀ sweep) and S10 (exactness ablation). Every v4 and older
     *  dir is a DIFFERENT algorithm with different recall and must never be resumed into. */
    private static String signature(List<DatasetSpec> suite) {
        StringBuilder sb = new StringBuilder("v5;algo=eng05seed+trie+exact;");
        for (DatasetSpec s : suite)
            sb.append(s.tag).append(':').append(s.minUtilRatio).append(':').append(s.maxRegRatio)
              .append(':').append(s.s1Only).append(';');
        sb.append("mu=").append(ExpConfig.MU_PARTITION).append(';');       // proposed: parameter-free
        sb.append("muBase=").append(ExpConfig.muMin).append(',').append(ExpConfig.muMax).append(';');  // baselines only
        sb.append("runs=").append(ExpConfig.warmupRuns).append(',').append(ExpConfig.measuredRuns).append(';');
        sb.append("to=").append(ExpConfig.runTimeoutMs).append(";cov=").append(ExpConfig.coverageMaxN).append(';');
        sb.append("S=").append(ExpConfig.runS1Scalability).append(ExpConfig.runS2Compare)
          .append(ExpConfig.runS4Distribution).append(ExpConfig.runS5FineBatch).append(ExpConfig.runS6DeltaSweep)
          .append(ExpConfig.runS7RhoSweep).append(ExpConfig.runS8BatchScaling)
          .append(ExpConfig.runS9MuSweep).append(ExpConfig.runS10Exactness);
        sb.append(";s5x=").append(ExpConfig.s5ExtraDatasets);
        sb.append(";s6=").append(java.util.Arrays.toString(ExpConfig.S6_DELTA_MULT));
        sb.append(";s7=").append(java.util.Arrays.toString(ExpConfig.S7_RHO_MULT));
        sb.append(";s8=").append(java.util.Arrays.toString(ExpConfig.S8_BATCH_COUNTS)).append(ExpConfig.s6Datasets);
        sb.append(";s9=").append(java.util.Arrays.toString(ExpConfig.S9_MUS));
        sb.append(";T=").append(java.util.Arrays.toString(ExpConfig.effectiveThreadSweep()));
        return sb.toString();
    }

    private static String gitCommit() {
        try {
            File head = new File(".git/HEAD");
            if (!head.exists()) return "n/a";
            String h = new String(Files.readAllBytes(head.toPath())).trim();
            if (h.startsWith("ref:")) {
                File ref = new File(".git", h.substring(4).trim());
                if (ref.exists()) return new String(Files.readAllBytes(ref.toPath())).trim();
            }
            return h;
        } catch (Exception e) { return "n/a"; }
    }
}
