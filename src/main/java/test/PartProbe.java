package test;

import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the per-batch-exact variant (each batch mined once at its own natural threshold) at one batch
 * count, against the oracle. The question it exists for: the finer the partition, the smaller each
 * part and the looser the regularity bound relative to it -- the slack N_final/|part| -- and the
 * variant dies along exactly that curve (k=16 exhausted 24 GB in the followup run). This measures
 * whether the floor prune (-DfloorPrune) changes that outcome, and at what memory.
 *
 * <pre>
 *   java -Xmx6g -DfloorPrune -cp out test.PartProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03 16
 * </pre>
 */
public class PartProbe {

    public static void main(String[] args) throws Exception {
        String seqFile = args[0], euiFile = args[1];
        double delta = Double.parseDouble(args[2]), rho = Double.parseDouble(args[3]);
        int k = Integer.parseInt(args[4]);

        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ExpConfig.fineRatios(k, 0.25));
        Set<String> oracle = ExpUtil.oracleCanon(all, delta, rho);
        try {
            AlgoPRIncHUSP m = ExpConfig.newProposedPartition(Runtime.getRuntime().availableProcessors());
            // Report the flag the miner actually carries, not a re-parse of the property -- the one
            // run where those differed measured nothing and only this line exposed it.
            System.out.printf("%s  k=%d  |part|~%d  N=%d  oracle=%d  floorPrune=%s%n",
                    ExpUtil.datasetTag(seqFile), k, batches.get(1).size(), all.size(), oracle.size(),
                    m.floorPruneSeeds);
            long[] phase = new long[3];
            double[] phaseMem = new double[3];
            int[] held = new int[2];
            PeakMemoryMeter meter = new PeakMemoryMeter();
            Map<String, long[]> res = ExpUtil.run(m, batches, delta, rho, phase, meter, phaseMem, held);
            double peak = meter.peakMB();
            meter.close();
            System.out.printf("  HS=%d  recall=%.4f  peak=%.0f MB  held(end)=%d  ms=%d%n",
                    res.size(), ExpUtil.coverage(res, oracle), peak, held[1],
                    phase[0] + phase[1] + phase[2]);
        } catch (Throwable t) {
            System.out.println("  FAILED: " + t.getClass().getSimpleName());
        }
    }
}
