package test;

import algorithms.AlgoRHUSP;
import algorithms.AlgoRHUSPMiner;
import algorithms.QSeqDatabase;
import algorithms.SeqConverter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * <h1>Validate the CORRECTNESS of the oracle (AlgoRHUSP) on REAL DATA</h1>
 *
 * Compare the result set of the in-memory oracle {@link AlgoRHUSP} (used to measure coverage in
 * the experiments) against the <b>original RHusp</b> {@link AlgoRHUSPMiner} (a validated reference
 * implementation). If they diverge on some dataset/δ, the experiment's coverage column there is NOT
 * trustworthy.
 * <p>
 * Important: the oracle must be correct on LONG SEQUENCES / large datasets, not just on the pattern
 * set — ordering bugs (regularity via HashMap) have only surfaced on real data. Results are written
 * to {@code results/}.
 */
public class OracleValidation {

    /** One validation entry: dataset + (δ, ρ). */
    static final class Case {
        final String tag; final double delta, rho;
        Case(String tag, double delta, double rho) { this.tag = tag; this.delta = delta; this.rho = rho; }
    }

    /** Validation list — pick a moderate δ so both run yet still produce patterns. Edit freely. */
    static Case[] cases() {
        // Small/medium REAL datasets the in-memory (naive) miner can handle; moderate δ. BIBLE/FIFA/KOSARAK
        // are too large for the naive miner, so coverage is NOT validated there (performance benchmark only).
        return new Case[]{
            new Case("example",  0.10, 0.60),
            new Case("example2", 0.10, 0.60),
            new Case("SIGN",     0.03, 0.30),
            new Case("SIGN",     0.04, 0.60),
            new Case("LEVIATHAN", 0.06, 0.60),
            new Case("ONLINE_RETAIL_II_BEST", 0.04, 0.60),
        };
    }

    public static void main(String[] args) throws IOException {
        new File("results").mkdirs();
        String out = "results/oracle_validation_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new java.util.Date()) + ".csv";
        System.out.println("### ORACLE VALIDATION (AlgoRHUSP) vs original RHusp (AlgoRHUSPMiner) -> " + out + " ###");
        System.out.printf("%-24s %6s %5s %8s %8s %8s %8s %s%n",
                "dataset", "delta", "rho", "ref", "inmem", "only-ref", "only-im", "conclusion");

        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write("dataset,delta,rho,ref_count,inmem_count,only_ref,only_inmem,match,ref_ms,inmem_ms\n");
            for (Case c : cases()) {
                DatasetSpec spec = new DatasetSpec(c.tag, c.delta, c.rho, new double[]{1.0});
                if (!spec.available) { System.out.println("  (skipped, missing file: " + c.tag + ")"); continue; }
                try {
                    validateOne(c, spec.seqFile, spec.euiFile, w);
                } catch (Throwable e) {
                    System.out.printf("%-24s %6.3f %5.2f  ERROR: %s%n", c.tag, c.delta, c.rho, e.getClass().getSimpleName());
                    w.write(String.format("%s,%s,%s,-1,-1,-1,-1,ERROR,-1,-1%n", c.tag, c.delta, c.rho));
                    w.flush();
                }
            }
        }
        System.out.println("### DONE. Report: " + out + " ###");
    }

    static void validateOne(Case c, String seqFile, String euiFile, BufferedWriter w) throws IOException {
        // original RHusp (reference) — reads the file directly
        AlgoRHUSPMiner ref = new AlgoRHUSPMiner();
        long t0 = System.currentTimeMillis();
        ref.runAlgorithm(seqFile, euiFile, "results", c.tag, c.delta, c.rho);
        long refMs = System.currentTimeMillis() - t0;
        TreeSet<String> refSet = new TreeSet<>();
        for (String k : ref.finalPatterns.keySet()) refSet.add(SeqConverter.canonical(k));

        // in-memory oracle (AlgoRHUSP)
        QSeqDatabase db = new QSeqDatabase();
        db.loadExternalUtility(euiFile);
        List<List<int[]>> all = db.readSequences(seqFile);
        long UD = 0;
        for (List<int[]> s : all) for (int[] e : s) for (int k = 1; k < e.length; k += 2) UD += e[k];
        AlgoRHUSP m = new AlgoRHUSP(); m.parallel = false;
        long t1 = System.currentTimeMillis();
        Map<String, long[]> res = m.mine(all, (long) Math.ceil(c.delta * UD), (int) (c.rho * all.size()));
        long inmemMs = System.currentTimeMillis() - t1;
        TreeSet<String> inmemSet = new TreeSet<>();
        for (String k : res.keySet()) inmemSet.add(SeqConverter.canonical(k));

        TreeSet<String> onlyRef = new TreeSet<>(refSet);   onlyRef.removeAll(inmemSet);
        TreeSet<String> onlyIm  = new TreeSet<>(inmemSet); onlyIm.removeAll(refSet);
        boolean match = onlyRef.isEmpty() && onlyIm.isEmpty();

        System.out.printf("%-24s %6.3f %5.2f %8d %8d %8d %8d  %s%n",
                c.tag, c.delta, c.rho, refSet.size(), inmemSet.size(), onlyRef.size(), onlyIm.size(),
                match ? "MATCH" : "MISMATCH");
        w.write(String.format("%s,%s,%s,%d,%d,%d,%d,%s,%d,%d%n",
                c.tag, c.delta, c.rho, refSet.size(), inmemSet.size(), onlyRef.size(), onlyIm.size(),
                match ? "MATCH" : "MISMATCH", refMs, inmemMs));
        w.flush();
    }
}
