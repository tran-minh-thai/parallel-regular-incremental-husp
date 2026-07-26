package test;

import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sweeps the growth factor nu, which replaces the final-size assumption by a claim about how far the
 * database may still grow: the seeding bound becomes nu*rho*N_current instead of rho*N_final.
 *
 * <p>The point of the sweep is the trade-off curve. nu = 1 is the baseline's rule, pruning against a
 * threshold that later rises; large nu retains everything, which is exact and unbounded. What the
 * numbers have to show is where recall is bought and what it costs in held patterns and heap, on the
 * axis that carries the loss.
 *
 * <pre>
 *   java -cp out test.GrowthSweep datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03 A
 * </pre>
 */
public class GrowthSweep {

    private static final double[] NU = {1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 10.0};

    public static void main(String[] args) throws Exception {
        String seqFile = args[0], euiFile = args[1];
        double delta = Double.parseDouble(args[2]), rho = Double.parseDouble(args[3]);
        double[] ratios = args[4].equals("B") ? ExpConfig.SCEN_B : ExpConfig.SCEN_A;

        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ratios);
        Set<String> oracle = ExpUtil.oracleCanon(all, delta, rho);
        System.out.printf("%s  dist=%s  N=%d  oracle=%d%n",
                ExpUtil.datasetTag(seqFile), args[4], all.size(), oracle.size());
        System.out.printf("%6s  %8s  %8s  %10s  %9s  %8s%n",
                "nu", "recall", "HS", "held", "peak MB", "ms");

        for (double nu : NU) {
            try {
                AlgoPRIncHUSP m = ExpConfig.newProposed(Runtime.getRuntime().availableProcessors());
                m.growthFactor = nu;
                m.evictPermanentlyIrregular = true;
                long[] phase = new long[3];
                double[] phaseMem = new double[3];
                int[] held = new int[2];
                PeakMemoryMeter meter = new PeakMemoryMeter();
                Map<String, long[]> res = ExpUtil.run(m, batches, delta, rho, phase, meter, phaseMem, held);
                double peak = meter.peakMB();
                meter.close();
                System.out.printf("%6.1f  %8.4f  %8d  %10d  %9.1f  %8d%n",
                        nu, ExpUtil.coverage(res, oracle), res.size(), held[1], peak,
                        phase[0] + phase[1] + phase[2]);
            } catch (Throwable t) {
                System.out.printf("%6.1f  %8s%n", nu, t.getClass().getSimpleName());
            }
        }
    }
}
