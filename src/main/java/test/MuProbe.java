package test;

import algorithms.AlgoRIncHUSP;
import algorithms.AlgoPRIncHUSP;
import algorithms.IncrementalHUSPMiner;
import algorithms.QSeqDatabase;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DIAGNOSTIC probe (not a benchmark): split into B4 batches, measure #HS (and %coverage vs the
 * exact RHusp-remine oracle) of RIncHusp at several μ + P-RIncHUSP. Shows how μ governs the number
 * of baseline patterns, and why seed-once + coarse accumulation limits RIncHusp. Does not modify
 * the mechanism.
 */
public class MuProbe {
    static final double[] B4 = {0.4, 0.2, 0.2, 0.2};

    public static void main(String[] args) throws Exception {
        probe("example", "datasets/example_seq.txt", "datasets/example_eui.txt", 0.10, 0.60);
        if (new java.io.File("datasets/SIGN_seq.txt").exists())
            probe("SIGN", "datasets/SIGN_seq.txt", "datasets/SIGN_eui.txt", 0.03, 0.30);
    }

    static void probe(String tag, String seq, String eui, double delta, double rho) throws Exception {
        QSeqDatabase db = new QSeqDatabase();
        db.loadExternalUtility(eui);
        List<List<int[]>> all = db.readSequences(seq);
        List<List<List<int[]>>> batches = ExpUtil.split(all, B4);
        Set<String> oracle = ExpUtil.oracleCanon(all, delta, rho);

        System.out.printf("%n== %s | n=%d δ=%.3f ρ=%.2f | B4 | oracle(RHusp)=%d ==%n",
                tag, all.size(), delta, rho, oracle.size());
        System.out.printf("%-26s %8s %8s %8s%n", "algorithm / μ", "#HS", "correct", "%cover");

        for (double mu : new double[]{0.4, 0.6, 0.8}) {
            AlgoRIncHUSP r = new AlgoRIncHUSP(); r.bufferFactor = mu;
            report("RIncHusp μ=" + mu, r, batches, delta, rho, oracle);
        }
        AlgoPRIncHUSP p = new AlgoPRIncHUSP(); p.numThreads = 1;
        p.buffer.bufferFactorMin = 0.4; p.buffer.bufferFactorMax = 0.9;
        report("P-RIncHUSP (μ∈[.4,.9])", p, batches, delta, rho, oracle);
    }

    static void report(String name, IncrementalHUSPMiner m, List<List<List<int[]>>> b,
                       double delta, double rho, Set<String> oracle) {
        Map<String, long[]> res = ExpUtil.run(m, b, delta, rho);
        int hits = ExpUtil.hits(res, oracle);
        System.out.printf("%-26s %8d %8d %7.1f%%%n",
                name, res.size(), hits, 100.0 * hits / Math.max(1, oracle.size()));
    }
}
