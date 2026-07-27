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

    /**
     * How many patterns the miner is actually holding, as opposed to how many it returns.
     *
     * <p>The answer set and the tracked set are not the same thing, and only the first was ever
     * reported. A pattern that fails the regularity test is dropped from the output but stays in the
     * tracked set, because a later batch can make it regular again; discovery likewise keeps every
     * candidate it raises, since the partition lemma only promises a pattern surfaces in SOME part.
     * So the tracked set is a superset of the answer, it never shrinks, and nothing measured it —
     * which left the peak-memory figures with no quantity to correlate against.
     *
     * @return the number of tracked patterns, or -1 if the miner does not distinguish the two sets
     */
    default int trackedCount() { return -1; }

    /**
     * Switch the regularity constraint to an ABSOLUTE bound: a pattern is regular when its maximum
     * period is at most {@code b} sequences, at every point in time, regardless of how large the
     * database grows. Zero (the default everywhere) keeps the relative bound maxReg = rho * N.
     *
     * <p>The distinction carries the whole memory story. The relative bound RISES with N, so every
     * incremental phase must protect patterns against a future, looser test -- a retention band that
     * is provably unprunable and whose width, N_final/|slice|, is what exhausted the heap. A constant
     * bound has no future to protect against: the same pruning a full re-mine enjoys becomes sound
     * in every phase, permanently.
     */
    default void setAbsoluteMaxReg(int b) {}

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
