package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.QSeqDatabase;

import java.util.List;
import java.util.Map;

/**
 * Compact PERFORMANCE probe: run P-RIncHUSP ONCE (4 batches, distribution A) on one dataset, print
 * runtime / peak memory / #HS / #SHS / #explored nodes. Use to quickly measure the impact of an
 * optimization (without running the full S1-S11 suite). Arguments: [seq] [eui] [δ] [ρ] [threads].
 */
public class PerfProbe {
    public static void main(String[] args) throws Exception {
        String seq = args.length > 0 ? args[0] : "datasets/SIGN_seq.txt";
        String eui = args.length > 1 ? args[1] : "datasets/SIGN_eui.txt";
        double delta = args.length > 2 ? Double.parseDouble(args[2]) : 0.03;
        double rho   = args.length > 3 ? Double.parseDouble(args[3]) : 0.30;
        int threads  = args.length > 4 ? Integer.parseInt(args[4]) : 1;

        QSeqDatabase db = new QSeqDatabase();
        db.loadExternalUtility(eui);
        List<List<int[]>> all = db.readSequences(seq);
        List<List<List<int[]>>> b = ExpUtil.split(all, ExpConfig.SCEN_A);

        AlgoPRIncHUSP m = new AlgoPRIncHUSP();
        m.numThreads = threads;
        m.buffer.bufferFactorMin = 0.4; m.buffer.bufferFactorMax = 0.9;

        m.hintTotalSequences(all.size());          // ρ·N_final for sound regularity pruning at seeding time
        System.gc();
        long t0 = System.currentTimeMillis();
        m.initialBuild(b.get(0), delta, rho);
        long tInit = System.currentTimeMillis() - t0;
        for (int i = 1; i < b.size(); i++) m.processBatch(b.get(i));
        long tTotal = System.currentTimeMillis() - t0;
        Map<String, long[]> hs = m.getHighUtilityPatterns();
        m.close();

        System.out.printf("%n=== PerfProbe %s | n=%d δ=%.4f ρ=%.2f T=%d ===%n",
                ExpUtil.datasetTag(seq), all.size(), delta, rho, threads);
        System.out.printf("init=%d ms | total(4 batches)=%d ms | peak=%.1f MB | HS=%d | SHS=%d | explored=%d%n",
                tInit, tTotal, m.peakMemoryMB(), hs.size(), m.bufferedCount(), m.getExploredNodes());
    }
}
