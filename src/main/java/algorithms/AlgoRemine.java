package algorithms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>Re-mine-from-scratch baseline</h1>
 *
 * The NAIVE incremental strategy: on every batch, discard all prior work and re-run the exact static
 * RHUSP miner ({@link AlgoRHUSP}) over the ENTIRE accumulated database. It produces exactly the ground
 * truth each batch (it IS a full re-mine), so it isolates the value of INCREMENTAL maintenance: the gap
 * between this baseline and RIncHusp / P-RIncHUSP is precisely the cost avoided by not re-scanning
 * history. Sequential (the fair "just re-run the miner" competitor). Used on light datasets only,
 * re-mining a large DB every batch is intentionally the expensive thing the proposed avoids.
 */
public class AlgoRemine implements IncrementalHUSPMiner {

    /** Absolute regularity bound; 0 = relative. */
    public int absoluteMaxReg = 0;
    @Override public void setAbsoluteMaxReg(int b) { this.absoluteMaxReg = b; }

    private final List<List<int[]>> db = new ArrayList<>();
    private double minUtilRatio, maxRegRatio;
    private Map<String, long[]> result = new HashMap<>();
    private double peakMB = 0;

    @Override public String name() { return "Remine"; }

    @Override
    public void initialBuild(List<List<int[]>> dOld, double minUtilRatio, double maxRegRatio) {
        this.minUtilRatio = minUtilRatio; this.maxRegRatio = maxRegRatio;
        db.addAll(dOld);
        remine();
        sampleMemory();
    }

    @Override
    public long processBatch(List<List<int[]>> deltaD) {
        long t0 = System.currentTimeMillis();
        db.addAll(deltaD);
        remine();                                     // full re-mine over the whole accumulated DB
        sampleMemory();
        return System.currentTimeMillis() - t0;
    }

    /** Exact static RHUSP mine over the current full DB at minUtil = δ·totalUtility, maxReg = ρ·|DB|. */
    private void remine() {
        long totalUtil = 0;
        for (List<int[]> s : db) for (int[] e : s) for (int k = 1; k < e.length; k += 2) totalUtil += e[k];
        long minUtil = (long) Math.ceil(minUtilRatio * totalUtil);
        int maxReg = absoluteMaxReg > 0 ? absoluteMaxReg : (int) (maxRegRatio * db.size());
        AlgoRHUSP m = new AlgoRHUSP(); m.parallel = false;
        result = m.mine(db, minUtil, maxReg);
    }

    private void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        double mb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        if (mb > peakMB) peakMB = mb;
    }

    @Override public Map<String, long[]> getHighUtilityPatterns() { return result; }
    @Override public double peakMemoryMB() { return peakMB; }
}
