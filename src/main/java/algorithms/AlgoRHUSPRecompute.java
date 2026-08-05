package algorithms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h1>RHusp-Recompute: "re-mine from scratch" reference</h1>
 *
 * Non-incremental baseline: whenever a batch {@code ΔD} arrives, accumulate it into the database
 * and RE-RUN the static RHUSP mining engine {@link AlgoRHUSP} from scratch over the whole
 * {@code D_new}. Reference point showing the cost of forgoing incremental updates: every batch
 * must re-scan the entire history (core difference from {@link AlgoPRIncHUSP}, where DEUCS only
 * accumulates ΔD).
 * <p>
 * With {@code parallel=true} (default) it represents "proposed-static re-run" (R/--/yes, the
 * "proposed-static" row in the manuscript); with {@code parallel=false} it yields the sequential
 * RHusp oracle used to measure coverage. The runner {@link RunIncremental} uses it as oracle.
 */
public class AlgoRHUSPRecompute implements IncrementalHUSPMiner {

    private final List<List<int[]>> accumulated = new ArrayList<>();
    private final AlgoRHUSP miner = new AlgoRHUSP();
    private double minUtilRatio, maxRegRatio;
    private long minUtil, totalUD;
    private int maxReg;
    private Map<String, long[]> results = new java.util.HashMap<>();
    private double peakMB = 0;

    public AlgoRHUSPRecompute() { miner.parallel = true; }

    @Override public String name() { return "RHusp-Recompute"; }

    @Override
    public void initialBuild(List<List<int[]>> dOld, double minUtilRatio, double maxRegRatio) {
        this.minUtilRatio = minUtilRatio; this.maxRegRatio = maxRegRatio;
        processBatch(dOld);
    }

    @Override
    public long processBatch(List<List<int[]>> deltaD) {
        long t0 = System.currentTimeMillis();
        accumulated.addAll(deltaD);                     // accumulate
        recomputeThresholds();
        results = miner.mine(accumulated, minUtil, maxReg);   // RE-RUN over everything
        sampleMemory();
        return System.currentTimeMillis() - t0;
    }

    private void recomputeThresholds() {
        totalUD = 0;
        for (List<int[]> seq : accumulated)
            for (int[] ev : seq)
                for (int k = 1; k < ev.length; k += 2) totalUD += ev[k];
        minUtil = (long) Math.ceil(minUtilRatio * totalUD);
        maxReg = (int) (maxRegRatio * accumulated.size());
    }

    private void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        double mb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        if (mb > peakMB) peakMB = mb;
    }

    @Override public Map<String, long[]> getHighUtilityPatterns() { return results; }
    @Override public double peakMemoryMB() { return peakMB; }
}
