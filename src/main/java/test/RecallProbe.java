package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.QSeqDatabase;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RECALL probe: compare P-RIncHUSP (4 batches, distribution A) against the RHusp remine oracle over
 * the full DB at an arbitrary δ. Prints oracle #HS, then one line per thread count with #HS, correct
 * count, %recall, runtime and peak memory. Arguments: [seq eui δ ρ T[,T...]].
 *
 * <p>The oracle depends only on (data, δ, ρ) — never on the thread count — so it is built ONCE and
 * reused for every T in the list. That matters on a large dataset: on KOSARAK the oracle costs about
 * 95 minutes against a few seconds of mining, so probing {@code 1,10} as two separate runs would pay
 * for the same oracle twice.
 *
 * <pre>
 *   java -Xmx24g -cp out test.RecallProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.03 0.30 10
 *   java -Xmx24g -cp out test.RecallProbe datasets/KOSARAK_seq.txt datasets/KOSARAK_eui.txt 0.015 0.30 1,10
 * </pre>
 */
public class RecallProbe {
    public static void main(String[] args) throws Exception {
        String seq = args.length > 0 ? args[0] : "datasets/SIGN_seq.txt";
        String eui = args.length > 1 ? args[1] : "datasets/SIGN_eui.txt";
        double delta = args.length > 2 ? Double.parseDouble(args[2]) : 0.005;
        double rho   = args.length > 3 ? Double.parseDouble(args[3]) : 0.30;
        int[] threadList = parseThreads(args.length > 4 ? args[4] : "4");

        QSeqDatabase db = new QSeqDatabase();
        db.loadExternalUtility(eui);
        List<List<int[]>> all = db.readSequences(seq);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);

        long t0 = System.currentTimeMillis();
        Set<String> oracle = ExpUtil.oracleCanon(all, delta, rho);   // built once — independent of T
        long tOracle = System.currentTimeMillis() - t0;

        System.out.printf("%n=== RecallProbe %s | n=%d delta=%.4f rho=%.2f | prune=%s ===%n",
                ExpUtil.datasetTag(seq), all.size(), delta, rho,
                ExpConfig.newProposed(1).seedPruneByFinalN ? "rho*N_final(sound)" : "rho*N_current(approx)");
        System.out.printf("oracle HS=%d (%d ms)%n", oracle.size(), tOracle);

        for (int threads : threadList) {
            AlgoPRIncHUSP m = ExpConfig.newProposed(threads);
            long t1 = System.currentTimeMillis();
            Map<String, long[]> res = ExpUtil.run(m, b, delta, rho);
            long tRun = System.currentTimeMillis() - t1;
            int hits = ExpUtil.hits(res, oracle);
            System.out.printf("  T=%-3d | HS=%d correct=%d recall=%.2f%% (%d ms) | peak=%.1f MB%n",
                    threads, res.size(), hits, 100.0 * hits / Math.max(1, oracle.size()), tRun, m.peakMemoryMB());
        }
    }

    /** "10" or "1,10" -> thread counts, in the order given. */
    private static int[] parseThreads(String s) {
        String[] p = s.split(",");
        int[] t = new int[p.length];
        for (int i = 0; i < p.length; i++) t[i] = Integer.parseInt(p[i].trim());
        return t;
    }
}
