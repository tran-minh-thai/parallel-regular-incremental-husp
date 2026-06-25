package algorithms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * <h1>P-RIncHUSP — Parallel Regular Incremental High-Utility Sequential Pattern miner</h1>
 *
 * Proposed algorithm. Mechanism: <b>SHS maintenance + promotion</b>, in two phases:
 * <ul>
 *   <li>{@link #initialBuild} (D_old): enumerate patterns by PEU bound (parallel over root branches),
 *       keeping only SHS/HS patterns — i.e. {@code totalUtility >= θ} — into the flat list {@code pats}.
 *       The enumeration tree exists only temporarily during recursion (VUL "scratch" reused per depth,
 *       so no per-node allocation), hence millions of useless PEU-promising nodes are not retained
 *       (a major optimization).</li>
 *   <li>{@link #processBatch} (ΔD): for each kept SHS/HS pattern, re-match on the new sequences
 *       (max-measure, same semantics as at build time) to add utility + update regularity; then
 *       reclassify (SHS reaching {@code minUtil} is promoted to HS). Each pattern is independent,
 *       so the work is parallel and contention-free.</li>
 * </ul>
 * Buffer threshold θ(t) = μ(t)·minUtil with adaptive μ ({@link AdaptiveBuffer}). No entirely new
 * pattern is generated outside the kept set (consistent with SHS maintenance); a pattern below θ in
 * D_old cannot be promoted.
 */
public class AlgoPRIncHUSP implements IncrementalHUSPMiner {

    static final int I_EXT = 0;   // join within the same itemset
    static final int S_EXT = 1;   // join in sequence order

    public int numThreads = Runtime.getRuntime().availableProcessors();
    public String label = "P-RIncHUSP";

    /**
     * Choice of regularity pruning threshold at seeding time:
     * <ul>
     *   <li>{@code false} (default): ρ·N_<b>current</b> (RIncHusp/paper style) — strong pruning,
     *       approximates high coverage (few patterns regular-at-final-DB are lost). Required to make
     *       low δ (dense data) feasible to run.</li>
     *   <li>{@code true}: ρ·N_<b>final</b> (requires hint) — sound, no recall loss, but ineffective
     *       when D_old is small.</li>
     * </ul>
     */
    public boolean seedPruneByFinalN = false;

    // ----- State persistent across batches -----
    private final QSeqDatabase data = new QSeqDatabase();
    private final DEUCS deucs = new DEUCS();
    public final AdaptiveBuffer buffer = new AdaptiveBuffer();   // public: μ configuration/ablation

    /** Tracked SHS/HS pattern set (flat, no persistent tree). Utility/regularity updated each batch. */
    private Pat[] pats = new Pat[0];
    private final ConcurrentLinkedQueue<Pat> patsQueue = new ConcurrentLinkedQueue<>();

    private double minUtilRatio, maxRegRatio;
    private long minUtil;
    private int maxReg;
    private int seedMaxReg;                           // seeding-time regularity threshold = ρ·N_final (safe pruning)
    private int hintedTotalN = 0;                     // total N (if hinted by harness) — see hintTotalSequences
    private double bufferThreshold;                  // θ(t) = μ(t)·minUtil

    private final Map<String, long[]> highUtility = new ConcurrentHashMap<>();
    private final Set<String> bufferedPatterns = ConcurrentHashMap.newKeySet();
    private final LongAdder exploredNodes = new LongAdder();
    private double peakMB = 0;
    private boolean initialized = false;

    /** Shared ForkJoinPool for the whole algorithm lifetime (lazily created, size = numThreads). */
    private ForkJoinPool pool;

    /** Per-thread workspace: VUL scratch per depth + path buffers (no per-node allocation). */
    private final ThreadLocal<Workspace> ws = ThreadLocal.withInitial(Workspace::new);

    @Override public String name() { return label; }

    /** A kept SHS/HS pattern: path (ext/item) + accumulated utility and regularity. */
    static final class Pat {
        final int[] ext, item;            // ext[0]=S_EXT; item[k] = k-th item
        long utility; int lastSeqId; int maxInnerPeriod;
        Pat(int[] ext, int[] item, long utility, int lastSeqId, int maxInnerPeriod) {
            this.ext = ext; this.item = item;
            this.utility = utility; this.lastSeqId = lastSeqId; this.maxInnerPeriod = maxInnerPeriod;
        }
        int trueMaxPer(int n) { return lastSeqId == -1 ? Integer.MAX_VALUE : Math.max(maxInnerPeriod, n - lastSeqId); }
    }

    /** Per-thread reusable buffers for enumeration (avoid allocation in the hot loop). */
    static final class Workspace {
        VerticalUtilityList[] vul = new VerticalUtilityList[16];
        int[] ext = new int[16];
        int[] item = new int[16];
        // reused maps for localCandidates (clear instead of allocating per node) — reduces heavy GC churn under multithreading
        final IntHashSet promising = new IntHashSet(16);
        final IntLongHashMap lapeu = new IntLongHashMap(16);
        // per-sequence end-position dedup when building multi-position VUL (buildInto); 2 frontiers for matchPatternUtil DP
        final IntLongHashMap posMap = new IntLongHashMap(16);
        IntLongHashMap frontA = new IntLongHashMap(16), frontB = new IntLongHashMap(16);
        VerticalUtilityList vul(int d) {
            if (d >= vul.length) grow(d);
            VerticalUtilityList v = vul[d];
            if (v == null) { v = new VerticalUtilityList(64); vul[d] = v; }
            return v;
        }
        private void grow(int d) {
            int n = Math.max(d + 1, vul.length * 2);
            vul = java.util.Arrays.copyOf(vul, n);
            ext = java.util.Arrays.copyOf(ext, n);
            item = java.util.Arrays.copyOf(item, n);
        }
    }

    // =====================================================================
    //  INITIALIZATION — enumerate D_old, keep SHS/HS patterns (totalUtility >= θ)
    // =====================================================================
    @Override
    public void initialBuild(List<List<int[]>> dOld, double minUtilRatio, double maxRegRatio) {
        this.minUtilRatio = minUtilRatio; this.maxRegRatio = maxRegRatio;
        deucs.incUpdate(dOld);
        deucs.buildAdjacency();          // adjacency index for fast candidate generation (used only in enumeration)
        data.appendBatch(dOld);
        recomputeThresholds(0, 0, dOld.size(), 0);   // first batch: μ = μ_min
        // Seeding-time regularity pruning threshold = ρ·N_final (if total N is known) — cut irregular
        // branches early without losing patterns regular-at-final-DB. No hint -> use current maxReg
        // (may prune slightly too aggressively).
        seedMaxReg = (seedPruneByFinalN && hintedTotalN > data.numSequences)
                ? (int) (maxRegRatio * hintedTotalN)     // sound (ρ·N_final) — no recall loss
                : maxReg;                                // default ρ·N_current — strong pruning, approximate (paper)
        staticBuild();
        pats = patsQueue.toArray(new Pat[0]);
        patsQueue.clear();
        deucs.release();            // DEUCS only serves seeding; the incremental phase does not need it -> release (less residual memory)
        initialized = true;
        classify();
        sampleMemory();
    }

    /**
     * Enumerate patterns with PEU bound >= θ on D_old, parallel over root branches (prefix equivalence
     * classes). Each thread traverses one root branch via sequential DFS with VUL scratch reused per
     * depth, so no contention and no per-node allocation. Patterns with {@code totalUtility >= θ} are
     * added to {@code patsQueue}.
     */
    private void staticBuild() {
        VerticalUtilityList rootVul = syntheticRoot(0, data.numSequences);
        List<Integer> anchors = new ArrayList<>();
        for (Integer i : deucs.SWU.keySet()) if (deucs.swu(i) >= bufferThreshold) anchors.add(i);
        final int A = anchors.size();
        runParallelIndexed(A, i -> {
            Workspace w = ws.get();
            int item = anchors.get(i);
            VerticalUtilityList v = w.vul(0); v.reset();
            buildInto(w, rootVul, item, false, v);
            if (v.isEmpty() || v.peuUpperBound < bufferThreshold) return;
            if (v.maxInnerPeriod > seedMaxReg) return;   // regularity pruning (anti-monotone) — see extend()
            w.ext[0] = S_EXT; w.item[0] = item;
            enumerate(w, v, 0, item);
        });
    }

    /** Record the pattern if SHS/HS, then extend candidates (PEU-pruned) — sequential recursion within one branch. */
    private void enumerate(Workspace w, VerticalUtilityList vul, int depth, int lastItem) {
        exploredNodes.increment();
        if (vul.totalUtility >= bufferThreshold && vul.lastSeqId != -1) {
            int len = depth + 1;
            patsQueue.add(new Pat(java.util.Arrays.copyOf(w.ext, len), java.util.Arrays.copyOf(w.item, len),
                    vul.totalUtility, vul.lastSeqId, vul.maxInnerPeriod));
        }
        for (int z : localCandidates(w, lastItem, vul, true))  extend(w, vul, depth, z, true);
        for (int z : localCandidates(w, lastItem, vul, false)) extend(w, vul, depth, z, false);
    }

    /** Build the child VUL into depth-d scratch (reused); recurse if PEU bound >= θ. */
    private void extend(Workspace w, VerticalUtilityList parentVul, int depth, int z, boolean iExt) {
        int d = depth + 1;
        VerticalUtilityList child = w.vul(d);   // ensure ext/item have room up to d
        child.reset();
        buildInto(w, parentVul, z, iExt, child);
        if (child.isEmpty() || child.peuUpperBound < bufferThreshold) return;   // prune by PEU bound
        // ANTI-MONOTONE regularity pruning: extension shrinks the set of sequences containing the
        // pattern -> inner periods only increase, so if the prefix is irregular (maxInnerPeriod>maxReg)
        // every extension is also irregular -> cut early.
        // (The final period depends on N and is checked in classify; here only the inner period is used for safe pruning.)
        if (child.maxInnerPeriod > seedMaxReg) return;
        w.ext[d] = iExt ? I_EXT : S_EXT;
        w.item[d] = z;
        enumerate(w, child, d, z);
    }

    // =====================================================================
    //  INCREMENTAL — re-match each SHS/HS pattern on new sequences, then promote
    // =====================================================================
    @Override
    public long processBatch(List<List<int[]>> deltaD) {
        long t0 = System.currentTimeMillis();
        if (!initialized) { initialBuild(deltaD, minUtilRatio, maxRegRatio); return System.currentTimeMillis() - t0; }

        long prevUD = data.totalDbUtility; int prevN = data.numSequences;
        int[] range = data.appendBatch(deltaD);        // append at tail -> [lo,hi); update totalDbUtility/numSequences
        recomputeThresholds(AdaptiveBuffer.batchUtility(deltaD), prevUD, deltaD.size(), prevN);

        maintain(range[0], range[1]);                  // re-match each pattern on new sequences (parallel over patterns)
        classify();                                    // promote SHS->HS / reclassify

        sampleMemory();
        return System.currentTimeMillis() - t0;
    }

    /**
     * Incremental maintenance: for each kept pattern, re-match on the new sequences {@code [lo,hi)}
     * (max-measure, same semantics as at build time) to add utility + update periods. Since
     * {@code u(α,DB)=Σ_S u(α,S)} is additive over sequences, this is an exact update without
     * re-scanning history. Each pattern is independent, so the work is parallel.
     */
    private void maintain(int lo, int hi) {
        final Pat[] arr = pats;
        runParallelIndexed(arr.length, idx -> {
            Pat p = arr[idx];
            for (int s = lo; s < hi; s++) {
                long u = matchPatternUtil(p.ext, p.item, s);
                if (u != Long.MIN_VALUE) {                 // pattern occurs in sequence s
                    int gap = (p.lastSeqId == -1) ? (s + 1) : (s - p.lastSeqId);
                    if (gap > p.maxInnerPeriod) p.maxInnerPeriod = gap;
                    p.utility += u;
                    p.lastSeqId = s;
                }
            }
        });
    }

    /**
     * Exact max-measure utility of pattern {@code (ext,item)} in sequence {@code seqId} via a
     * frontier-based dynamic program: at each step keep all non-dominated end positions (max utility
     * per position) rather than a single greedy state — matches the oracle semantics. Returns
     * {@link Long#MIN_VALUE} if absent.
     */
    private long matchPatternUtil(int[] ext, int[] item, int seqId) {
        final int[] items = data.items, eventItemStart = data.eventItemStart, seqEventStart = data.seqEventStart;
        final long[] utils = data.utils;
        final int firstEv = seqEventStart[seqId];
        final int evCount = seqEventStart[seqId + 1] - firstEv;
        final int L = item.length;
        Workspace w = ws.get();
        IntLongHashMap cur = w.frontA, nxt = w.frontB;
        cur.clear();
        // step 0: item[0] at every position (s-ext from root)
        int it0 = item[0];
        for (int e = 0; e < evCount; e++) {
            int ge = firstEv + e, p0 = eventItemStart[ge], p1 = eventItemStart[ge + 1];
            for (int j = 0; j < p1 - p0; j++) if (items[p0 + j] == it0) cur.putMax((e << J_BITS) | j, utils[p0 + j]);
        }
        if (cur.isEmpty()) return Long.MIN_VALUE;
        for (int k = 1; k < L; k++) {
            boolean iExt = (ext[k] == I_EXT);
            int itk = item[k];
            nxt.clear();
            for (int sl = 0; sl < cur.slotCount(); sl++) {
                if (!cur.occupied(sl)) continue;
                int key = cur.keyAt(sl); long acc = cur.valAt(sl);
                int pe = key >>> J_BITS, pj = key & J_MASK;
                int startE = iExt ? pe : pe + 1;
                int endE   = iExt ? Math.min(pe + 1, evCount) : evCount;
                for (int e = startE; e < endE; e++) {
                    int ge = firstEv + e, p0 = eventItemStart[ge], p1 = eventItemStart[ge + 1];
                    int startJ = (iExt && e == pe) ? pj + 1 : 0;
                    for (int j = startJ; j < p1 - p0; j++)
                        if (items[p0 + j] == itk) nxt.putMax((e << J_BITS) | j, acc + utils[p0 + j]);
                }
            }
            if (nxt.isEmpty()) return Long.MIN_VALUE;
            IntLongHashMap tmp = cur; cur = nxt; nxt = tmp;     // swap frontiers
        }
        long best = Long.MIN_VALUE;
        for (int sl = 0; sl < cur.slotCount(); sl++) if (cur.occupied(sl) && cur.valAt(sl) > best) best = cur.valAt(sl);
        w.frontA = cur; w.frontB = nxt;                          // store back references (post-swap)
        return best;
    }

    // =====================================================================
    //  MATCHING / CANDIDATE GENERATION (used in the D_old enumeration phase)
    // =====================================================================

    /** Synthetic root VUL for seqIds in [lo,hi): (seqId, eventIdx=-1, itemIdx=-1, matchUtility=0). */
    private VerticalUtilityList syntheticRoot(int lo, int hi) {
        VerticalUtilityList vul = new VerticalUtilityList(hi - lo);
        for (int s = lo; s < hi; s++) vul.add(s, -1, -1, 0, 0);
        return vul;
    }

    private static final int J_BITS = 12, J_MASK = (1 << J_BITS) - 1;   // position encoding (e<<12)|j; j<4096

    /**
     * Build a multi-position child VUL: for each sequence, keep all non-dominated end positions of the
     * pattern extended by {@code z} (dedup by position, keep max utility). Required for the exact
     * max-measure: a parent position with lower utility may still yield a better extension at a later
     * step. {@code out.finalizeAggregates} at the end computes aggregate = Σ max per sequence (matches
     * oracle semantics).
     */
    private void buildInto(Workspace w, VerticalUtilityList parent, int z, boolean iExt, VerticalUtilityList out) {
        final int[] items = data.items, eventItemStart = data.eventItemStart, seqEventStart = data.seqEventStart;
        final long[] utils = data.utils, rems = data.rems;
        final IntLongHashMap pos = w.posMap;
        int idx = 0;
        while (idx < parent.size) {
            final int s = parent.seqIds[idx];
            final int firstEv = seqEventStart[s];
            final int evCount = seqEventStart[s + 1] - firstEv;
            pos.clear();
            // gather all parent entries of sequence s (contiguous since parent is ordered by ascending seqId); for each position of z keep the MAX utility
            while (idx < parent.size && parent.seqIds[idx] == s) {
                int pe = parent.eventIdxs[idx], pj = parent.itemIdxs[idx];
                long pu = parent.matchUtils[idx];
                int startE = iExt ? pe : pe + 1;
                int endE   = iExt ? Math.min(pe + 1, evCount) : evCount;
                for (int e = startE; e < endE; e++) {
                    int ge = firstEv + e, p0 = eventItemStart[ge], p1 = eventItemStart[ge + 1];
                    int startJ = (iExt && e == pe) ? pj + 1 : 0;
                    for (int j = startJ; j < p1 - p0; j++)
                        if (items[p0 + j] == z) pos.putMax((e << J_BITS) | j, pu + utils[p0 + j]);
                }
                idx++;
            }
            // emit the non-dominated end positions of sequence s
            for (int sl = 0; sl < pos.slotCount(); sl++) {
                if (!pos.occupied(sl)) continue;
                int key = pos.keyAt(sl), e = key >>> J_BITS, j = key & J_MASK;
                out.add(s, e, j, pos.valAt(sl), rems[eventItemStart[firstEv + e] + j]);
            }
        }
        out.finalizeAggregates();
    }

    private static final int[] EMPTY_INT = new int[0];

    /** Local candidate set C(α): {z adjacent to lastItem with weight >= θ} further filtered by LAPEU bound >= θ. */
    private int[] localCandidates(Workspace w, int lastItem, VerticalUtilityList vul, boolean iExt) {
        List<long[]> neighbors = (iExt ? deucs.adjInEvent : deucs.adjAfter).get(lastItem);
        if (neighbors == null) return EMPTY_INT;
        IntHashSet promising = w.promising; promising.clear();   // reuse (no per-node allocation)
        for (long[] ny : neighbors)
            if (ny[1] >= bufferThreshold) promising.add((int) ny[0]);
        if (promising.isEmpty()) return EMPTY_INT;

        final int[] items = data.items, eventItemStart = data.eventItemStart, seqEventStart = data.seqEventStart;
        final long[] utils = data.utils, rems = data.rems;
        IntLongHashMap lapeu = w.lapeu; lapeu.clear();           // reuse
        for (int idx = 0; idx < vul.size; idx++) {
            final int eSeqId = vul.seqIds[idx], eEventIdx = vul.eventIdxs[idx], eItemIdx = vul.itemIdxs[idx];
            final long eMatchUtil = vul.matchUtils[idx];
            final int firstEv = seqEventStart[eSeqId];
            final int evCount = seqEventStart[eSeqId + 1] - firstEv;
            int startE = iExt ? eEventIdx : eEventIdx + 1;
            int endE   = iExt ? Math.min(eEventIdx + 1, evCount) : evCount;
            for (int k = startE; k < endE; k++) {
                int ge = firstEv + k;
                int p0 = eventItemStart[ge], p1 = eventItemStart[ge + 1];
                int startJ = (iExt && k == eEventIdx) ? eItemIdx + 1 : 0;
                for (int j = startJ; j < p1 - p0; j++) {
                    int pos = p0 + j;
                    int z = items[pos];
                    if (promising.contains(z))
                        lapeu.addTo(z, eMatchUtil + utils[pos] + rems[pos]);
                }
            }
        }
        int[] prom = promising.toArray();
        int cnt = 0;
        for (int z : prom) if (lapeu.get(z, 0L) >= bufferThreshold) prom[cnt++] = z;
        int[] cand = java.util.Arrays.copyOf(prom, cnt);
        java.util.Arrays.sort(cand);
        return cand;
    }

    // =====================================================================
    //  CLASSIFICATION — promote SHS->HS
    // =====================================================================
    private void classify() {
        highUtility.clear();
        bufferedPatterns.clear();
        final int n = data.numSequences;
        for (Pat p : pats) {
            if (p.lastSeqId == -1) continue;
            int trueMaxPer = p.trueMaxPer(n);
            if (trueMaxPer > maxReg) continue;                  // irregular -> discard (Corollary 2)
            if (p.utility >= minUtil) highUtility.put(formatPattern(p.ext, p.item), new long[]{p.utility, trueMaxPer});
            else if (p.utility >= bufferThreshold) bufferedPatterns.add(formatPattern(p.ext, p.item));
        }
    }

    /** Canonical pattern key {@code <(i i)(i)>}: items in the same itemset separated by a space, a new itemset by ")(". */
    private static String formatPattern(int[] ext, int[] item) {
        StringBuilder sb = new StringBuilder("<(");
        for (int k = 0; k < item.length; k++) {
            if (k > 0 && ext[k] == S_EXT) sb.append(")(");
            else if (k > 0) sb.append(' ');
            sb.append(item[k]);
        }
        return sb.append(")>").toString();
    }

    private void recomputeThresholds(long batchUtil, long prevUD, int batchSize, int prevN) {
        minUtil = (long) Math.ceil(minUtilRatio * data.totalDbUtility);
        maxReg  = (int) (maxRegRatio * data.numSequences);
        double bufferFactor = buffer.computeBufferFactor(batchUtil, prevUD, batchSize, prevN, minUtilRatio);
        bufferThreshold = bufferFactor * minUtil;
    }

    // =====================================================================
    //  PARALLELISM (coarse-grained) + UTILITIES
    // =====================================================================
    private ForkJoinPool pool() {
        if (pool == null) pool = new ForkJoinPool(Math.max(1, numThreads));
        return pool;
    }

    private <T> void runParallel(List<T> items, Consumer<T> body) {
        if (numThreads <= 1 || items.size() <= 1) { for (T t : items) body.accept(t); return; }
        try { pool().submit(() -> items.parallelStream().forEach(body)).get(); }
        catch (Exception e) { propagate(e); }
    }

    private void runParallelIndexed(int n, IntConsumer body) {
        if (numThreads <= 1 || n <= 1) { for (int i = 0; i < n; i++) body.accept(i); return; }
        try { pool().submit(() -> IntStream.range(0, n).parallel().forEach(body)).get(); }
        catch (Exception e) { propagate(e); }
    }

    private static void propagate(Exception e) {
        Throwable c = (e.getCause() != null) ? e.getCause() : e;
        if (c instanceof RuntimeException) throw (RuntimeException) c;
        if (c instanceof Error) throw (Error) c;
        throw new RuntimeException(c);
    }

    @Override public void close() {
        if (pool != null) { pool.shutdown(); pool = null; }
    }

    @Override public void hintTotalSequences(int totalN) { this.hintedTotalN = totalN; }

    private void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        double mb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        if (mb > peakMB) peakMB = mb;
    }

    @Override public Map<String, long[]> getHighUtilityPatterns() { return highUtility; }
    @Override public int bufferedCount() { return bufferedPatterns.size(); }
    @Override public double peakMemoryMB() { return peakMB; }
    public long getMinUtil() { return minUtil; }
    public int getMaxReg() { return maxReg; }
    public double getBufferThreshold() { return bufferThreshold; }
    public long getExploredNodes() { return exploredNodes.sum(); }
}
