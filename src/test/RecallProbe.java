package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.QSeqDatabase;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RECALL probe: compare P-RIncHUSP (4 batches, distribution A) against the RHusp remine oracle
 * over the full DB at an arbitrary δ. Prints oracle #HS, P-RIncHUSP #HS, correct count, %recall,
 * runtime, and peak memory. Arguments: [seq eui δ ρ T].
 */
public class RecallProbe {
    public static void main(String[] args) throws Exception {
        String seq = args.length > 0 ? args[0] : "datasets/SIGN_seq.txt";
        String eui = args.length > 1 ? args[1] : "datasets/SIGN_eui.txt";
        double delta = args.length > 2 ? Double.parseDouble(args[2]) : 0.005;
        double rho   = args.length > 3 ? Double.parseDouble(args[3]) : 0.30;
        int threads  = args.length > 4 ? Integer.parseInt(args[4]) : 4;

        QSeqDatabase db = new QSeqDatabase();
        db.loadExternalUtility(eui);
        List<List<int[]>> all = db.readSequences(seq);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);

        long t0 = System.currentTimeMillis();
        Set<String> oracle = ExpUtil.oracleCanon(all, delta, rho);
        long tOracle = System.currentTimeMillis() - t0;

        AlgoPRIncHUSP m = ExpConfig.newProposed(threads);
        long t1 = System.currentTimeMillis();
        Map<String, long[]> res = ExpUtil.run(m, b, delta, rho);
        long tRun = System.currentTimeMillis() - t1;
        int hits = ExpUtil.hits(res, oracle);

        System.out.printf("%n=== RecallProbe %s | n=%d delta=%.4f rho=%.2f T=%d | prune=%s ===%n",
                ExpUtil.datasetTag(seq), all.size(), delta, rho, threads,
                m.seedPruneByFinalN ? "rho*N_final(sound)" : "rho*N_current(approx)");
        System.out.printf("oracle HS=%d (%d ms) | P-RIncHUSP HS=%d correct=%d recall=%.2f%% (%d ms) | peak=%.1f MB%n",
                oracle.size(), tOracle, res.size(), hits, 100.0 * hits / Math.max(1, oracle.size()), tRun, m.peakMemoryMB());
    }
}
