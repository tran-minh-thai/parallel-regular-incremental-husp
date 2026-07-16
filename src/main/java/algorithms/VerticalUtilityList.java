package algorithms;

import java.util.Arrays;

/**
 * Vertical utility list (VUL) of a pattern {@code α} — the manuscript, §Data structures.
 * <p>
 * Stored as <b>parallel primitive arrays</b> (memory-efficient). Each entry is a single
 * <b>non-dominated end position</b> of the pattern in one sequence: a sequence may have multiple
 * entries (different end positions giving different utilities) — required for the <b>exact
 * max-measure</b>: the utility of an extension depends on the end position, so keeping a single
 * position (greedy) would miss matches (the max-utility position may block a better extension at
 * another position). Matches the RHusp oracle semantics.
 * <p>
 * Entries are added with non-decreasing seqId; the aggregate ({@link #totalUtility} = Σ max per
 * sequence, etc.) is computed by {@link #finalizeAggregates()} after adding completes (merge entries
 * of the same sequence, take MAX).
 */
public class VerticalUtilityList {

    public int[]  seqIds;
    public int[]  eventIdxs;     // matching end itemset
    public int[]  itemIdxs;      // item position within that itemset
    public long[] matchUtils;    // utility accumulated up to this end position
    public long[] remUtils;      // remaining utility after the position
    public int    size = 0;

    public long totalUtility = 0;     // Σ_sequence max(matchUtils) = u(α) under the max-measure
    public long peuUpperBound = 0;    // Σ_sequence max(matchUtils+remUtils): PEU upper bound
    public int  lastSeqId = -1;       // most recent distinct sequence (for regularity)
    public int  maxInnerPeriod = 0;   // largest inner period (excluding the final period — see Pat.trueMaxPer)
    public int  maxIntraGap = 0;      // largest gap between CONSECUTIVE occurrences only (no initial gap
                                      // from seq 0) — the sound regularity prune for a window [lo,hi)
                                      // enumeration where "distance from seq 0" is meaningless

    public VerticalUtilityList() { this(4); }
    public VerticalUtilityList(int capacity) {
        int c = Math.max(capacity, 2);
        seqIds = new int[c]; eventIdxs = new int[c]; itemIdxs = new int[c];
        matchUtils = new long[c]; remUtils = new long[c];
    }

    /** Add an end position (with non-decreasing seqId). Does not update aggregates — call {@link #finalizeAggregates()} afterwards. */
    public void add(int seqId, int eventIdx, int itemIdx, long matchUtility, long remainingUtility) {
        if (size == seqIds.length) grow();
        seqIds[size] = seqId; eventIdxs[size] = eventIdx; itemIdxs[size] = itemIdx;
        matchUtils[size] = matchUtility; remUtils[size] = remainingUtility;
        size++;
    }

    /**
     * Compute the exact max-measure aggregates: merge entries of the same seqId (already contiguous
     * since added with ascending seqId), take {@code max(matchUtils)} for utility and
     * {@code max(matchUtils+remUtils)} for the PEU bound per sequence; regularity over the distinct seqIds.
     */
    public void finalizeAggregates() {
        totalUtility = 0; peuUpperBound = 0; lastSeqId = -1; maxInnerPeriod = 0; maxIntraGap = 0;
        int i = 0;
        while (i < size) {
            int s = seqIds[i];
            long maxU = Long.MIN_VALUE, maxPeu = Long.MIN_VALUE;
            while (i < size && seqIds[i] == s) {
                if (matchUtils[i] > maxU) maxU = matchUtils[i];
                long peu = matchUtils[i] + remUtils[i];
                if (peu > maxPeu) maxPeu = peu;
                i++;
            }
            totalUtility  += maxU;
            peuUpperBound += maxPeu;
            int gap = (lastSeqId == -1) ? (s + 1) : (s - lastSeqId);
            if (gap > maxInnerPeriod) maxInnerPeriod = gap;
            if (lastSeqId != -1 && gap > maxIntraGap) maxIntraGap = gap;   // consecutive-occurrence gaps only
            lastSeqId = s;
        }
    }

    public boolean isEmpty() { return size == 0; }

    /** Clear for reuse (keep arrays) — for the per-depth VUL scratch in staticBuild. */
    public void reset() {
        size = 0; totalUtility = 0; peuUpperBound = 0; lastSeqId = -1; maxInnerPeriod = 0; maxIntraGap = 0;
    }

    private void grow() {
        int n = size * 2;
        seqIds = Arrays.copyOf(seqIds, n);   eventIdxs = Arrays.copyOf(eventIdxs, n);
        itemIdxs = Arrays.copyOf(itemIdxs, n);
        matchUtils = Arrays.copyOf(matchUtils, n);  remUtils = Arrays.copyOf(remUtils, n);
    }
}
