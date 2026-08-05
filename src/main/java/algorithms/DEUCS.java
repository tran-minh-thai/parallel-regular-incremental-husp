package algorithms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Directed Estimated-Utility Co-occurrence Structure (DEUCS).
 * <p>
 * Defined in the manuscript (Definition 6) and the manuscript, §Data structures:
 * a sparse two-dimensional hash table keyed by ORDERED item pairs {@code (x -> y)}, with values
 * equal to the sequence-weighted utility (SWU) of sequences containing both {@code x} and {@code y}
 * under the corresponding relation. Two components:
 * <ul>
 *   <li>{@link #coOccurInEvent}: co-occurrence within the SAME event;</li>
 *   <li>{@link #coOccurAfter}: co-occurrence in SEQUENCE ORDER.</li>
 * </ul>
 * Also maintains the total database utility {@link #totalDbUtility} and the per-item {@link #SWU} table.
 * <p>
 * Core incremental property (Lemma 1, {@code lem:additive}): updates only ADD the contribution of
 * the new batch {@code ΔD}, never re-scanning history; the result equals a full recompute over
 * {@code D_new} and preserves the upper-bound guarantee.
 */
public class DEUCS {

    /** DEUCS_I[(x->y)] for co-occurrence within the same event. Key packed = pack(x,y). */
    public final Map<Long, Long> coOccurInEvent = new HashMap<>();
    /** DEUCS_S[(x->y)] for co-occurrence in sequence order. */
    public final Map<Long, Long> coOccurAfter = new HashMap<>();
    /** SWU[x] = sum of u(S) over sequences containing x. */
    public final Map<Integer, Long> SWU = new HashMap<>();
    /** Total database utility. */
    public long totalDbUtility = 0;

    /**
     * Adjacency index: item {@code x} -> list of {@code [neighbor y, value]}. Allows candidate
     * generation for a given {@code lastItem} in O(degree) rather than O(|DEUCS|) full-map scans.
     * Rebuilt after each {@link #incUpdate} by {@link #buildAdjacency()}.
     */
    public final Map<Integer, List<long[]>> adjInEvent = new HashMap<>();   // i-extension
    public final Map<Integer, List<long[]>> adjAfter   = new HashMap<>();   // s-extension

    static long pack(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

    public long getI(int x, int y) { return coOccurInEvent.getOrDefault(pack(x, y), 0L); }
    public long getS(int x, int y) { return coOccurAfter.getOrDefault(pack(x, y), 0L); }
    public long swu(int x)         { return SWU.getOrDefault(x, 0L); }

    /**
     * Release ALL structures (co-occurrence + adjacency + SWU); call AFTER seeding completes.
     * P-RIncHUSP uses DEUCS only for candidate generation during staticBuild; the incremental
     * phase re-matches directly and no longer needs it.
     */
    public void release() {
        coOccurInEvent.clear(); coOccurAfter.clear(); SWU.clear();
        adjInEvent.clear(); adjAfter.clear();
    }

    /** Rebuild the adjacency index from coOccurInEvent/coOccurAfter; call AFTER incUpdate. */
    public void buildAdjacency() {
        adjInEvent.clear(); adjAfter.clear();
        buildAdj(coOccurInEvent, adjInEvent);
        buildAdj(coOccurAfter, adjAfter);
    }

    private static void buildAdj(Map<Long, Long> ds, Map<Integer, List<long[]>> adj) {
        for (Map.Entry<Long, Long> e : ds.entrySet()) {
            int x = (int) (e.getKey() >>> 32), y = (int) (long) e.getKey();
            adj.computeIfAbsent(x, k -> new ArrayList<>()).add(new long[]{y, e.getValue()});
        }
    }

    /**
     * Algorithm 2: IncUpdateDEUCS: additive incremental update from batch {@code batch} only.
     * Cost O(|ΔD|·L̄²), independent of the history size (the manuscript, §Complexity).
     */
    public void incUpdate(java.util.List<java.util.List<int[]>> batch) {
        for (java.util.List<int[]> S : batch) {
            long uS = 0;
            for (int[] ev : S) for (int k = 1; k < ev.length; k += 2) uS += ev[k];
            totalDbUtility += uS;

            // SWU: each item appearing in S receives u(S) (counted once per sequence)
            java.util.Set<Integer> seenItems = new java.util.HashSet<>();
            for (int[] ev : S)
                for (int j = 0; j < ev.length; j += 2)
                    if (seenItems.add(ev[j])) SWU.merge(ev[j], uS, Long::sum);

            // DEUCS_I: every pair (x,y) with x!=y in the same event (dedup per sequence)
            java.util.Set<Long> seenPairI = new java.util.HashSet<>();
            for (int[] ev : S) {
                int m = ev.length / 2;
                for (int a = 0; a < m; a++)
                    for (int b = a + 1; b < m; b++) {
                        int x = ev[a * 2], y = ev[b * 2];
                        if (x == y) continue;
                        long key = pack(Math.min(x, y), Math.max(x, y));
                        if (seenPairI.add(key)) coOccurInEvent.merge(key, uS, Long::sum);
                    }
            }

            // DEUCS_S: ordered pair (x -> y) with x in an earlier event, y in a later event.
            // Dedup per pair/sequence: each directed pair contributes u(S) exactly once per sequence.
            java.util.Set<Long> seenPair = new java.util.HashSet<>();
            java.util.Set<Integer> before = new java.util.LinkedHashSet<>();
            for (int[] ev : S) {
                for (int j = 0; j < ev.length; j += 2) {
                    int y = ev[j];
                    for (int x : before) {
                        long key = pack(x, y);
                        if (seenPair.add(key)) coOccurAfter.merge(key, uS, Long::sum);
                    }
                }
                for (int j = 0; j < ev.length; j += 2) before.add(ev[j]);
            }
        }
    }
}
