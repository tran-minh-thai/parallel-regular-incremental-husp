package algorithms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>Par-Remine: the PARALLEL static miner re-run on every batch</h1>
 *
 * Wraps {@link AlgoRHUSPMinerParallel} (the companion study's parallel RHUSP engine: CSR layout,
 * work stealing, recursive dynamic load balancing) and RE-RUNS it from scratch over the whole
 * accumulated database whenever a batch arrives.
 * <p>
 * This is the <b>decisive baseline</b> for a parallel INCREMENTAL miner. It answers the question a
 * reviewer will certainly ask: <i>"a parallel static miner already exists, why not simply re-run it
 * when new data arrives?"</i> The gap between this baseline and P-RIncHUSP is exactly the value of
 * parallel incremental maintenance, measured on top of (not against) the parallel mining engine.
 * <p>
 * A re-mine keeps no state by definition, so each batch instantiates a FRESH miner: nothing leaks
 * between batches. {@link #processBatch} returns the miner's OWN reported mining time (Phase 1 +
 * Phase 2, excluding data-structure construction), the same quantity the companion paper reports,
 * which is generous to the baseline; the harness additionally records end-to-end wall clock.
 */
public class AlgoParRemine implements IncrementalHUSPMiner {

    public int numThreads = Runtime.getRuntime().availableProcessors();
    /** STRAT_RDLB = the companion paper's proposed scheduler (its best configuration). */
    public int parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
    public boolean denseBuffers = true;

    private final List<List<int[]>> db = new ArrayList<>();
    private double minUtilRatio, maxRegRatio;

    /** Absolute regularity bound; 0 = relative. */
    public int absoluteMaxReg = 0;
    @Override public void setAbsoluteMaxReg(int b) { this.absoluteMaxReg = b; }
    private Map<String, long[]> result = new HashMap<>();
    private double peakMB = 0;
    /** Cumulative PURE mining time as reported by the engine itself (excludes CSR construction). */
    public long pureMiningMs = 0;

    @Override public String name() { return "ParRemine"; }

    @Override
    public void initialBuild(List<List<int[]>> dOld, double minUtilRatio, double maxRegRatio) {
        this.minUtilRatio = minUtilRatio;
        this.maxRegRatio = maxRegRatio;
        db.addAll(dOld);
        remine();
    }

    @Override
    public long processBatch(List<List<int[]>> deltaD) {
        db.addAll(deltaD);
        return remine();                       // batch cost = a full re-mine of everything seen so far
    }

    /** Re-mine the entire accumulated DB with a FRESH parallel engine; return its reported mining ms. */
    private long remine() {
        AlgoRHUSPMinerParallel m = new AlgoRHUSPMinerParallel();
        m.numThreads = numThreads;
        m.parallelStrategy = parallelStrategy;
        m.denseBuffers = denseBuffers;
        m.useEUCS = true;
        m.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
        m.useRegPruning = true;
        if (absoluteMaxReg > 0) m.forcedMaxReg = absoluteMaxReg;
        m.runAlgorithmInMemory(db, minUtilRatio, maxRegRatio);

        Map<String, long[]> out = new HashMap<>(m.finalPatterns.size() * 2);
        for (Map.Entry<String, AlgoRHUSPMinerParallel.PatternResult> e : m.finalPatterns.entrySet())
            out.put(e.getKey(), new long[]{e.getValue().utility, e.getValue().periodicity});
        result = out;

        double pk = m.getPeakMemoryMB();
        if (pk > peakMB) peakMB = pk;
        long ms = m.getRuntimeMs();
        pureMiningMs += ms;
        return ms;
    }

    @Override public Map<String, long[]> getHighUtilityPatterns() { return result; }
    @Override public double peakMemoryMB() { return peakMB; }
}
