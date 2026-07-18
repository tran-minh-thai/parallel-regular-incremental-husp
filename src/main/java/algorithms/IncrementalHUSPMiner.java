package algorithms;

import java.util.List;
import java.util.Map;

/**
 * Common interface for all incremental RHUSP mining algorithms (baseline and proposed),
 * enabling uniform orchestration by the experiment runner {@link RunIncremental}.
 * <p>
 * Workflow model: {@link #initialBuild} on the historical database {@code D_old}, then
 * repeated {@link #processBatch} for each tail-appended batch {@code ΔD}. After each batch,
 * the current high-utility result set is obtained via {@link #getHighUtilityPatterns}.
 */
public interface IncrementalHUSPMiner {

    /** Display name (for reporting/comparison). */
    String name();

    /**
     * Initialize on the historical database.
     * @param dOld     initial sequences
     * @param minUtilRatio δ — minUtil = δ × totalDbUtility
     * @param maxRegRatio ρ — maxReg = ρ × numSequences
     */
    void initialBuild(List<List<int[]>> dOld, double minUtilRatio, double maxRegRatio);

    /** Process one tail-appended batch {@code ΔD}; return batch processing time (ms). */
    long processBatch(List<List<int[]>> deltaD);

    /** Current set of regular high-utility patterns: pattern -> (utility, periodicity). */
    Map<String, long[]> getHighUtilityPatterns();

    /** Number of patterns currently held in the buffer (0 if the algorithm uses no buffer). */
    default int bufferedCount() { return 0; }

    /** Wall-clock spent in the query-time discovery/reconcile phase (0 if the algorithm has none).
     *  Reported separately from build (seeding) and incremental (maintenance) so the per-phase cost
     *  breakdown can show discovery, which for this miner is a distinct and often dominant phase. */
    default long discoveryMs() { return 0; }

    /** Recorded peak memory (MB). */
    double peakMemoryMB();

    /** Release resources (e.g. a shared ForkJoinPool). Default is no-op. */
    default void close() {}

    /**
     * Hint of the TOTAL number of sequences after all batches are loaded (known in advance during
     * evaluation). An algorithm may use it to set the seeding regularity threshold = ρ·N_final
     * (safe pruning that does not lose patterns regular at the final DB). Default ignores it.
     */
    default void hintTotalSequences(int totalN) {}
}
