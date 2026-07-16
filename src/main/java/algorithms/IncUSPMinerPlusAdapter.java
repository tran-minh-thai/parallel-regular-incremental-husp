package algorithms;

import common.Pattern;
import common.Sequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter bringing the <b>IncUSP-Miner+</b> baseline [IncUSP2018] into the shared
 * incremental harness ({@link IncrementalHUSPMiner}). IncUSP-Miner+ mines high-utility
 * sequences INCREMENTALLY but does NOT consider regularity -> its result set is HUSP
 * (a superset of RHUSP).
 * <p>
 * Role in the paper (the manuscript, line "IncUSP-Miner+"): an ablation isolating the
 * <b>cost of the regularity constraint</b> in the incremental setting — IncUSP-Miner+
 * finds more patterns (including non-regular ones) and is therefore more expensive;
 * P-RIncHUSP prunes the non-regular patterns.
 * <p>
 * The {@code maxRegRatio} parameter is IGNORED (this baseline has no notion of
 * regularity). The utility threshold uses the same δ ratio for a fair comparison.
 */
public class IncUSPMinerPlusAdapter implements IncrementalHUSPMiner {

    private IncUSPMinerPlusAlgorithm algo;
    private final List<Sequence> accumulated = new ArrayList<>();
    private int nextId = 0;
    private double peakMB = 0;
    private boolean initialized = false;
    private double minUtilRatio;

    @Override public String name() { return "IncUSP-Miner+"; }

    @Override
    public void initialBuild(List<List<int[]>> dOld, double minUtilRatio, double maxRegRatio) {
        this.minUtilRatio = minUtilRatio;
        this.algo = new IncUSPMinerPlusAlgorithm(minUtilRatio);   // Su = δ (ratio)
        List<Sequence> seqs = SeqConverter.toSequences(dOld, nextId);
        nextId += seqs.size();
        accumulated.addAll(seqs);
        algo.runInitialMining(seqs, SeqConverter.totalUtility(accumulated));
        initialized = true;
        sampleMemory();
    }

    @Override
    public long processBatch(List<List<int[]>> deltaD) {
        long t0 = System.currentTimeMillis();
        if (!initialized) { initialBuild(deltaD, minUtilRatio, 0); return System.currentTimeMillis() - t0; }
        List<Sequence> minUtilRatio = SeqConverter.toSequences(deltaD, nextId);
        nextId += minUtilRatio.size();
        accumulated.addAll(minUtilRatio);
        algo.runIncrementalMining(minUtilRatio, accumulated, SeqConverter.totalUtility(accumulated));
        sampleMemory();
        return System.currentTimeMillis() - t0;
    }

    /** HUSP result: pattern -> {utility, -1}. periodicity = -1 (regularity not measured). */
    @Override
    public Map<String, long[]> getHighUtilityPatterns() {
        Map<String, long[]> out = new HashMap<>();
        for (Map.Entry<Pattern, Double> e : algo.largeMap.entrySet())
            out.put(SeqConverter.canonical(e.getKey()), new long[]{Math.round(e.getValue()), -1});
        return out;
    }

    @Override public double peakMemoryMB() { return peakMB; }

    private void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        double mb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        if (mb > peakMB) peakMB = mb;
    }
}
