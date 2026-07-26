package test;

import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measures the two mechanisms that need nothing about the future: a retention bound read off the
 * observed feed, and eviction ordered by how far each pattern still is from qualifying.
 *
 * <p>The lookahead h says how many arrivals ahead to keep covered, and the bound it produces,
 * {@code rho * (N_t + h * b)}, uses only the current size and the mean of the batches actually seen.
 * The claim to check is that h buys coverage the way it should: every pattern that becomes regular
 * within h batches is returned, and the rest is what the caller knowingly gives up.
 *
 * <p>The budget then bounds the tracked set outright. Its column to watch is recall against the
 * budget: memory that is a fact about the machine, traded for patterns, with no threshold guessed.
 *
 * <pre>
 *   java -cp out test.LookaheadProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03 A
 * </pre>
 */
public class LookaheadProbe {

    private static final int[] H = {1, 2, 3, 5, 8, 12};
    private static final int[] BUDGET_MB = {0, 400, 200, 100};

    public static void main(String[] args) throws Exception {
        String seqFile = args[0], euiFile = args[1];
        double delta = Double.parseDouble(args[2]), rho = Double.parseDouble(args[3]);
        double[] ratios = args[4].equals("B") ? ExpConfig.SCEN_B : ExpConfig.SCEN_A;

        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ratios);
        Set<String> oracle = ExpUtil.oracleCanon(all, delta, rho);
        System.out.printf("%s  dist=%s  N=%d  batches=%d  oracle=%d%n",
                ExpUtil.datasetTag(seqFile), args[4], all.size(), batches.size(), oracle.size());

        System.out.println("\n-- lookahead h, no budget --");
        System.out.printf("%5s  %5s  %8s  %8s  %10s  %9s  %8s%n", "h", "h_eff", "recall", "HS", "held", "peak MB", "ms");
        for (int h : H) run(batches, delta, rho, oracle, h, 0, String.format("%5d", h));

        System.out.println("\n-- budget, at the h that covers the whole feed --");
        System.out.printf("%5s  %5s  %8s  %8s  %10s  %9s  %8s%n", "MB", "h_eff", "recall", "HS", "held", "peak MB", "ms");
        for (int mb : BUDGET_MB)
            run(batches, delta, rho, oracle, batches.size(), mb,
                    String.format("%5s", mb == 0 ? "none" : String.valueOf(mb)));
    }

    private static void run(List<List<List<int[]>>> batches, double delta, double rho,
                            Set<String> oracle, int h, int budgetMB, String label) {
        try {
            AlgoPRIncHUSP m = ExpConfig.newProposed(Runtime.getRuntime().availableProcessors());
            m.lookaheadBatches = h;
            m.memoryBudgetMB = budgetMB;
            m.evictPermanentlyIrregular = true;
            long[] phase = new long[3];
            double[] phaseMem = new double[3];
            int[] held = new int[2];
            PeakMemoryMeter meter = new PeakMemoryMeter();
            Map<String, long[]> res = ExpUtil.run(m, batches, delta, rho, phase, meter, phaseMem, held);
            double peak = meter.peakMB();
            meter.close();
            System.out.printf("%s  %5d  %8.4f  %8d  %10d  %9.1f  %8d%n",
                    label, m.effectiveLookahead(), ExpUtil.coverage(res, oracle), res.size(), held[1],
                    peak, phase[0] + phase[1] + phase[2]);
        } catch (Throwable t) {
            System.out.printf("%s  %5s  %8s%n", label, "-", t.getClass().getSimpleName());
        }
    }
}
