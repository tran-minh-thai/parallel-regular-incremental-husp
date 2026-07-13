package algorithms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
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
    /** A/B switch (test.OverheadProbe): {@code true} = inverted-index prune in maintain(); {@code false} = original cross-product. */
    public boolean useInvertedIndex = true;
    /**
     * Maintain strategy. {@code false} (default) = per-pattern re-match (each kept pattern DP-matched
     * against its candidate sequences). {@code true} = CONTENT-DRIVEN: build a prefix trie over the
     * kept patterns and, per new sequence, traverse the trie guided by the sequence's items —
     * matching a shared prefix ONCE serves all patterns extending it (baseline RIncHusp's efficiency),
     * parallelized over disjoint ascending sequence ranges. Cannot combine with {@link #lazy}
     * (the trie holds all patterns; laziness would need a hot-only trie rebuilt on tier changes).
     */
    public boolean trieMaintain = false;
    /** test.AdaptiveProbe: when true, μ(t) gates maintain — a pattern whose utility drops below the
     *  buffer threshold θ(t)=μ(t)·minUtil is evicted (marked inactive) and stops being re-matched. */
    public boolean evict = false;
    /** Trend-aware eviction: spare a below-θ pattern whose utility/minUtil ratio is RISING (it is
     *  converging on promotion). Fixes the dense-dataset failure where a scalar μ evicts the crowd
     *  of patterns approaching the boundary (μ's growth signals are blind to dataset geometry). */
    public boolean trendSpare = false;
    /** Minimum ratio increase per batch to count as "rising" (guards float noise). */
    public double trendEps = 1e-9;
    /**
     * LAZY buffer (hot/cold tiering with a SOUND dormancy bound) — supersedes hard eviction.
     * A pattern below θ(t) goes COLD: frozen, no longer re-matched. Each batch, its possible utility
     * gain over the skipped window is upper-bounded by {@code min_item Σ_batch SWU_batch(item)}
     * (the pattern occurs in a sequence ⇒ every one of its items does ⇒ gain ≤ the SWU any single
     * item accumulated). If {@code frozen + bound < θ(t)} the pattern is CERTIFIED still below θ —
     * stays cold at O(1) cost. Otherwise it is caught up EXACTLY over precisely the batches it
     * skipped (stored per-batch posting lists) and reactivated. Consequently the per-batch HS set is
     * EXACT (identical to Fix(μ_min) which never evicts) by construction — μ(t) now only tunes how
     * aggressively the buffer sleeps, i.e. cost, never recall. */
    public boolean lazy = false;
    // Lazy stats (read by test.AdaptiveProbe): tier-1 = O(items) per-item bound; tier-2 = posting-list
    // intersection bound (Σ u(S) over sequences containing ALL pattern items — pays the intersection
    // but skips the expensive DP matching; on a wake the intersection work is reused anyway).
    public long lazyBoundSkips = 0, lazyT2Skips = 0, lazyWakeups = 0;
    /**
     * Memory cap for the per-batch lazy bookkeeping, in posting-list ints (~4 bytes each; default
     * 50M ≈ 200 MB). Crossing it triggers a CHECKPOINT: force-wake every cold pattern (exact
     * catch-up — the same work Fix(μ_min) would have paid for those windows anyway), then drop ALL
     * per-batch tables. Nothing references the old batches afterwards, so memory returns to ~zero
     * and correctness is untouched. Sized for streams far beyond KOSARAK (measured ~6M ints there).
     */
    public long lazyMaxPostingInts = 50_000_000;
    private long postingInts = 0;                    // running posting-list footprint (ints)
    public int lazyCheckpoints = 0;                  // stat: how many times the cap fired
    /**
     * Engagement gate: freezing (and hence the per-batch bookkeeping) only kicks in once at least
     * this many below-minUtil candidates are tracked. With a tiny candidate set (KOSARAK: 19) the
     * bookkeeping scan of ΔD costs far more than the maintenance it could save (+29% measured), so
     * lazy stays dormant there and the run is bit-identical to lazy=false. Invariant kept: any batch
     * during which SOME pattern is cold gets full bookkeeping (catch-up windows must have no gaps).
     */
    public int lazyMinCandidates = 32;
    private int coldCount = 0;                       // #patterns currently frozen
    private int lastBufferedN = 0;                   // SHS count from the previous classify
    private boolean freezeAllowed = false;           // set per batch before classify

    /** Re-seed (discovery): on each processed batch, enumerate ΔD at a batch-scaled threshold and
     *  verify candidates exactly over the full DB — admits patterns that were below the seeding
     *  threshold in D_old but intensified later, and resurrects wrongly evicted ones. This is the
     *  only mechanism that can push recall ABOVE the seed-once ceiling (= Fix(μ_min)'s recall).
     *  Measured VERY expensive per-batch (28× runtime on LEVIATHAN); kept for ablation, off by default. */
    public boolean reseed = false;
    /** Run discovery only when μ(t) ≤ this value (risk-gated). 1.0 = every batch. */
    public double reseedTrigger = 1.0;
    /** κ scale on the discovery threshold θ_disc = κ·θ(t)·(batchUtil/totalUtil). κ=1 ("on pace")
     *  explodes combinatorially on small batches (few occurrences suffice); κ=2 targets patterns
     *  genuinely INTENSIFYING in ΔD while still catching HS-pace ones whenever κ·μ(t) ≤ 1. */
    public double reseedScale = 2.0;
    /** Safety valve: abort a batch's discovery outright if enumeration records more than this many
     *  candidates (all-or-nothing, so results stay deterministic; the batch simply gets no discovery). */
    public int reseedCandidateCap = 200_000;
    // Discovery stats (read by test.AdaptiveProbe).
    public int discCandidates = 0, discAccepted = 0, discResurrected = 0, discAborted = 0;

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

    /**
     * Fork-join work-stealing for the D_old seeding enumeration (the 94–97% cost). Root-level
     * partitioning alone leaves threads idle under skewed subtree sizes (few productive branches on
     * BIBLE, one giant branch on FIFA); forking large subtrees lets idle workers steal them. A subtree
     * with a VUL smaller than {@link #seedGrain} occurrences runs SEQUENTIALLY (reusing depth-scratch,
     * no per-node allocation) so forking overhead is confined to the expensive upper levels.
     */
    public boolean forkSeed = false;
    /** Occurrence-count (VUL size) below which a subtree is enumerated sequentially instead of forked. */
    public int seedGrain = 128;

    // ----- State persistent across batches -----
    private final QSeqDatabase data = new QSeqDatabase();
    private final DEUCS deucs = new DEUCS();
    public final AdaptiveBuffer buffer = new AdaptiveBuffer();   // public: μ configuration/ablation

    // ----- Enumeration context (staticBuild on D_old OR discovery on ΔD) -----
    // The tree walk (enumerate/extend/localCandidates) reads these instead of the build-time fields,
    // so the same machinery serves both call sites. Set before each (single-orchestrator) run.
    private DEUCS enumDeucs;             // candidate-generation structure for the current enumeration
    private double enumThreshold;        // record/prune threshold (θ₀ at seeding; θ_disc at discovery)
    private int enumMaxReg;              // regularity prune bound (seedMaxReg at seeding; maxReg at discovery)
    private boolean enumUseIntraGap;     // false: prune on maxInnerPeriod (full-DB seeding, counts the gap
                                         // from seq 0); true: prune on maxIntraGap only (window enumeration
                                         // at discovery — consecutive-occurrence gaps inside [lo,hi) are
                                         // true global gaps, so the prune stays sound and anti-monotone)
    private int enumCap;                 // candidate cap for the current enumeration (MAX_VALUE at seeding)
    private final java.util.concurrent.atomic.AtomicInteger enumCount = new java.util.concurrent.atomic.AtomicInteger();

    /** Thrown inside enumeration workers when {@link #enumCap} is exceeded; caught in discover(). */
    private static final class CapExceeded extends RuntimeException {
        CapExceeded() { super(null, null, false, false); }
    }

    /** key -> tracked Pat (active or evicted); built only when {@link #reseed} (dedup + resurrection). */
    private java.util.HashMap<String, Pat> patIndex;

    // ----- Lazy-buffer bookkeeping (only populated when {@link #lazy}) -----
    private int batchIdx = 0;                                            // 0 = D_old; increments per processBatch
    private final Map<Integer, Map<Integer, Long>> swuByBatch = new java.util.HashMap<>();   // batch -> item -> SWU_batch(item)
    private final Map<Integer, Map<Integer, Long>> iuByBatch = new java.util.HashMap<>();    // batch -> item -> Σ occurrence-utility (2nd bound)
    private final Map<Integer, Map<Integer, int[]>> invByBatch = new java.util.HashMap<>();  // batch -> posting lists (catch-up + tier-2)
    private final Map<Integer, long[]> utilByBatch = new java.util.HashMap<>();              // batch -> u(S) per sequence (tier-2 bound)
    private final Map<Integer, Integer> loByBatch = new java.util.HashMap<>();               // batch -> first seqId (aligns utilByBatch)

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
    // SHS (buffered) count only — we never enumerate the buffered patterns, so store an int, NOT a
    // Set<String>. Materializing a formatPattern() String per SHS pattern (rebuilt every batch in
    // classify) was the dominant memory sink when the SHS set explodes on dense data + skewed batch
    // distributions (millions of Strings), and is pure waste since only the COUNT is ever read.
    private int bufferedN = 0;
    private final LongAdder exploredNodes = new LongAdder();
    // Instrumentation (test.OverheadProbe): incremental re-match calls + misses; guarded by COUNT so
    // normal timing pays no LongAdder overhead (the count/time confound when calls differ by 5x).
    public static boolean COUNT = false;
    public final LongAdder matchCalls = new LongAdder();
    public final LongAdder matchMisses = new LongAdder();
    private double peakMB = 0;
    private boolean initialized = false;

    /** Shared ForkJoinPool for the whole algorithm lifetime (lazily created, size = numThreads). */
    private ForkJoinPool pool;

    /** Per-thread workspace: VUL scratch per depth + path buffers (no per-node allocation). */
    private final ThreadLocal<Workspace> ws = ThreadLocal.withInitial(Workspace::new);

    // ----- Content-driven maintain (trieMaintain): prefix trie over pats, built once (pats fixed after seeding) -----
    private TNode trieRoot;
    private int trieForPats = -1;                    // pats.length the trie was built for (rebuild if it grows)
    private final ThreadLocal<TrieWs> trieWs = ThreadLocal.withInitial(TrieWs::new);

    /** Trie node: children keyed by item, split into i-extension / s-extension; patIdx>=0 marks a kept pattern. */
    static final class TNode {
        final int id; int patIdx = -1;
        java.util.HashMap<Integer, TNode> iCh, sCh;
        TNode(int id) { this.id = id; }
    }

    /** Per-thread traversal scratch: dominance memo + per-sequence pattern-utility map (reused, cleared). */
    static final class TrieWs {
        final LongLongHashMap memo = new LongLongHashMap(256);
        final IntLongHashMap best = new IntLongHashMap(64);
    }

    @Override public String name() { return label; }

    /** A kept SHS/HS pattern: path (ext/item) + accumulated utility and regularity. */
    static final class Pat {
        final int[] ext, item;            // ext[0]=S_EXT; item[k] = k-th item
        long utility; int lastSeqId; int maxInnerPeriod;
        boolean active = true;            // false once evicted (utility fell below θ(t)); skipped in maintain
        double prevRatio = 0;             // utility/minUtil at the previous classify (trend-aware eviction)
        int coldFromBatch = -1;           // lazy mode: first batch NOT reflected in the frozen state (-1 = hot / hard-evicted)
        long t2Sum = 0;                   // incremental tier-2 bound: Σ u(S) over intersection seqs in batches (coldFromBatch..t2Batch]
        int t2Batch = -1;                 // last batch folded into t2Sum (avoids re-intersecting the whole window each batch)
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
        // Lazy mode: ALWAYS seed at μ_min — seeding defines the coverage ceiling (lazy can only
        // preserve what was seeded); μ(t)/FIX-μ only tiers hot/cold from batch 1 on. Without this a
        // Lazy-Fix(0.9) run would seed at 0.9 and inherit Fix(0.9)'s recall, defeating the design.
        if (lazy) bufferThreshold = buffer.bufferFactorMin * minUtil;
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
        if (reseed) {               // key index for discovery dedup + resurrection of evicted patterns
            patIndex = new java.util.HashMap<>(pats.length * 2);
            for (Pat p : pats) patIndex.put(formatPattern(p.ext, p.item), p);
        }
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
        enumDeucs = deucs; enumThreshold = bufferThreshold; enumMaxReg = seedMaxReg;
        enumUseIntraGap = false; enumCap = Integer.MAX_VALUE; enumCount.set(0);
        if (forkSeed && numThreads > 1) enumerateForked(0, data.numSequences);
        else enumerateRange(0, data.numSequences);
    }

    /**
     * Work-stealing variant of {@link #enumerateRange}: build the surviving root branches on the
     * calling thread, then hand each to the pool as a {@link EnumTask}. Large subtrees fork their
     * children (independent VULs) so idle workers steal them; small subtrees (VUL &lt; {@link #seedGrain})
     * fall back to the sequential scratch-reusing {@link #enumerate}. Identical output to enumerateRange.
     */
    private void enumerateForked(int lo, int hi) {
        VerticalUtilityList rootVul = syntheticRoot(lo, hi);
        Workspace w = ws.get();
        List<EnumTask> roots = new ArrayList<>();
        for (Integer i : enumDeucs.SWU.keySet()) {
            if (enumDeucs.swu(i) < enumThreshold) continue;
            int item = i;
            VerticalUtilityList v = new VerticalUtilityList(64);
            buildInto(w, rootVul, item, false, v);
            if (v.isEmpty() || v.peuUpperBound < enumThreshold) continue;
            if ((enumUseIntraGap ? v.maxIntraGap : v.maxInnerPeriod) > enumMaxReg) continue;
            roots.add(new EnumTask(new int[]{S_EXT}, new int[]{item}, v));
        }
        if (roots.isEmpty()) return;
        pool().invoke(new RecursiveAction() {
            protected void compute() { ForkJoinTask.invokeAll(roots); }
        });
    }

    /** One enumeration subtree (its full path + own VUL). Large → fork children; small → sequential. */
    private final class EnumTask extends RecursiveAction {
        final int[] ext, item;
        final VerticalUtilityList vul;
        EnumTask(int[] ext, int[] item, VerticalUtilityList vul) { this.ext = ext; this.item = item; this.vul = vul; }

        protected void compute() {
            final int depth = item.length - 1;
            final Workspace w = ws.get();
            if (vul.size < seedGrain) {                    // small subtree: sequential (scratch reuse, no fork)
                w.vul(depth);                              // ensure w.ext/w.item capacity up to depth
                System.arraycopy(ext, 0, w.ext, 0, depth + 1);
                System.arraycopy(item, 0, w.item, 0, depth + 1);
                enumerate(w, vul, depth, item[depth]);
                return;
            }
            exploredNodes.increment();                     // large subtree: record here, fork children
            if (vul.totalUtility >= enumThreshold && vul.lastSeqId != -1) {
                patsQueue.add(new Pat(ext, item, vul.totalUtility, vul.lastSeqId, vul.maxInnerPeriod));
                if (enumCount.incrementAndGet() > enumCap) throw new CapExceeded();
            }
            List<EnumTask> kids = new ArrayList<>();
            addChildren(w, kids, true);                    // i-extensions
            addChildren(w, kids, false);                   // s-extensions
            if (!kids.isEmpty()) invokeAll(kids);
        }

        /** Build child tasks for one extension type (mirrors enumerate/extend with independent VULs). */
        private void addChildren(Workspace w, List<EnumTask> kids, boolean iExt) {
            int lastItem = item[item.length - 1];
            for (int z : localCandidates(w, lastItem, vul, iExt)) {
                VerticalUtilityList child = new VerticalUtilityList(Math.max(16, vul.size));
                buildInto(w, vul, z, iExt, child);
                if (child.isEmpty() || child.peuUpperBound < enumThreshold) continue;
                if ((enumUseIntraGap ? child.maxIntraGap : child.maxInnerPeriod) > enumMaxReg) continue;
                int[] cext = java.util.Arrays.copyOf(ext, ext.length + 1);  cext[ext.length] = iExt ? I_EXT : S_EXT;
                int[] citem = java.util.Arrays.copyOf(item, item.length + 1); citem[item.length] = z;
                kids.add(new EnumTask(cext, citem, child));
            }
        }
    }

    /** Enumerate patterns with PEU bound >= {@link #enumThreshold} over sequences [lo,hi), parallel
     *  over root branches; SHS/HS hits go to {@code patsQueue}. Context: {@link #enumDeucs}/{@link #enumMaxReg}. */
    private void enumerateRange(int lo, int hi) {
        VerticalUtilityList rootVul = syntheticRoot(lo, hi);
        List<Integer> anchors = new ArrayList<>();
        for (Integer i : enumDeucs.SWU.keySet()) if (enumDeucs.swu(i) >= enumThreshold) anchors.add(i);
        final int A = anchors.size();
        runParallelIndexed(A, i -> {
            Workspace w = ws.get();
            int item = anchors.get(i);
            VerticalUtilityList v = w.vul(0); v.reset();
            buildInto(w, rootVul, item, false, v);
            if (v.isEmpty() || v.peuUpperBound < enumThreshold) return;
            if ((enumUseIntraGap ? v.maxIntraGap : v.maxInnerPeriod) > enumMaxReg) return;   // regularity pruning (anti-monotone) — see extend()
            w.ext[0] = S_EXT; w.item[0] = item;
            enumerate(w, v, 0, item);
        });
    }

    /** Record the pattern if SHS/HS, then extend candidates (PEU-pruned) — sequential recursion within one branch. */
    private void enumerate(Workspace w, VerticalUtilityList vul, int depth, int lastItem) {
        exploredNodes.increment();
        if (vul.totalUtility >= enumThreshold && vul.lastSeqId != -1) {
            int len = depth + 1;
            patsQueue.add(new Pat(java.util.Arrays.copyOf(w.ext, len), java.util.Arrays.copyOf(w.item, len),
                    vul.totalUtility, vul.lastSeqId, vul.maxInnerPeriod));
            if (enumCount.incrementAndGet() > enumCap) throw new CapExceeded();   // safety valve (discovery only)
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
        if (child.isEmpty() || child.peuUpperBound < enumThreshold) return;   // prune by PEU bound
        // ANTI-MONOTONE regularity pruning: extension shrinks the set of sequences containing the
        // pattern -> inner periods only increase, so if the prefix is irregular (maxInnerPeriod>maxReg)
        // every extension is also irregular -> cut early.
        // (The final period depends on N and is checked in classify; here only the inner period is used for safe pruning.)
        if ((enumUseIntraGap ? child.maxIntraGap : child.maxInnerPeriod) > enumMaxReg) return;
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
        long batchUtil = AdaptiveBuffer.batchUtility(deltaD);
        int[] range = data.appendBatch(deltaD);        // append at tail -> [lo,hi); update totalDbUtility/numSequences
        recomputeThresholds(batchUtil, prevUD, deltaD.size(), prevN);
        batchIdx++;

        // Engagement: bookkeeping is MANDATORY while anything is cold (window gaps would break
        // catch-up); otherwise it only runs once the candidate set is big enough to pay for itself.
        boolean book = lazy && coldCount > 0;
        freezeAllowed = lazy && (coldCount > 0 || lastBufferedN >= lazyMinCandidates);
        if (book) {                                    // bookkeeping for the dormancy bounds + catch-up
            computeLazyBatchStats(deltaD, range[0]);
            Map<Integer, int[]> inv = buildInvertedIndex(range[0], range[1]);
            for (int[] post : inv.values()) postingInts += post.length;
            invByBatch.put(batchIdx, inv);
        } else if (freezeAllowed) {
            // First engaged batch: nothing cold yet, but this classify may freeze -> those windows
            // start at batchIdx+1, so bookkeeping correctly begins NEXT batch (coldCount > 0 then).
        }
        maintain(range[0], range[1]);                  // re-match each pattern on new sequences (parallel over patterns)
        if (book) {                                    // certify-or-catch-up BEFORE classify -> per-batch HS stays exact
            boolean checkpoint = postingInts > lazyMaxPostingInts;
            wakeUncertainCold(checkpoint);
            if (checkpoint) clearLazyBookkeeping();    // every pattern is hot again -> old batches unreferenced
        }
        if (reseed && buffer.lastBufferFactor <= reseedTrigger)
            discover(deltaD, range[0], range[1], batchUtil);   // admit emerging patterns / resurrect evicted ones
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
        if (trieMaintain) { maintainTrie(lo, hi); return; }
        final Pat[] arr = pats;
        if (!useInvertedIndex) {                            // original |pats| x |Δseq| cross-product (A/B baseline)
            runParallelIndexed(arr.length, idx -> {
                Pat p = arr[idx];
                if (!p.active) return;
                for (int s = lo; s < hi; s++) matchAndUpdate(p, s);
            });
            return;
        }
        // Inverted index over the new sequences [lo,hi): item -> ascending seqIds containing it.
        // Prunes the cross-product — each pattern is re-matched only against sequences that contain
        // EVERY one of its items (intersection of posting lists; a necessary order-free condition for
        // occurrence), instead of every new sequence. Exact: a sequence missing any pattern item cannot
        // contain the pattern, so it would have returned MIN_VALUE and left utility/regularity unchanged.
        // Ascending seqId order preserves the consecutive-occurrence gap computation in matchAndUpdate.
        // In engaged-lazy batches the index was already built (and retained for catch-up) in
        // processBatch; otherwise (lazy dormant, or lazy off) build a transient one.
        Map<Integer, int[]> stored = lazy ? invByBatch.get(batchIdx) : null;
        final Map<Integer, int[]> inv = (stored != null) ? stored : buildInvertedIndex(lo, hi);
        runParallelIndexed(arr.length, idx -> {
            Pat p = arr[idx];
            if (!p.active) return;                          // evicted -> no longer maintained
            int[] cand = candidateSeqs(p, inv);
            if (cand == null) return;                      // some pattern item absent from the whole batch
            for (int s : cand) matchAndUpdate(p, s);
        });
    }

    /** Re-match pattern {@code p} on sequence {@code s}; if it occurs, add utility + update regularity.
     *  Only the owning thread (one pattern = one task) touches {@code p}, so the mutation is race-free. */
    private void matchAndUpdate(Pat p, int s) {
        long u = matchPatternUtil(p.ext, p.item, s);
        if (u != Long.MIN_VALUE) {                         // pattern occurs in sequence s
            int gap = (p.lastSeqId == -1) ? (s + 1) : (s - p.lastSeqId);
            if (gap > p.maxInnerPeriod) p.maxInnerPeriod = gap;
            p.utility += u;
            p.lastSeqId = s;
        }
    }

    // =====================================================================
    //  CONTENT-DRIVEN MAINTAIN (trieMaintain) — trie over pats, traverse per new sequence
    // =====================================================================

    /** Build the prefix trie from {@code pats} (once; pats is fixed after seeding). ext[0]=S_EXT (a
     *  root s-child); for k>=1, ext[k] chooses the i-/s-child map. Terminal node carries the pat index. */
    private void buildTrie() {
        int[] counter = {0};
        TNode root = new TNode(counter[0]++);
        for (int i = 0; i < pats.length; i++) {
            Pat p = pats[i];
            TNode cur = root;
            for (int k = 0; k < p.item.length; k++) {
                boolean iExt = (k > 0) && (p.ext[k] == I_EXT);
                java.util.HashMap<Integer, TNode> m = iExt
                        ? (cur.iCh != null ? cur.iCh : (cur.iCh = new java.util.HashMap<>()))
                        : (cur.sCh != null ? cur.sCh : (cur.sCh = new java.util.HashMap<>()));
                TNode c = m.get(p.item[k]);
                if (c == null) { c = new TNode(counter[0]++); m.put(p.item[k], c); }
                cur = c;
            }
            cur.patIdx = i;
        }
        trieRoot = root; trieForPats = pats.length;
    }

    /**
     * Content-driven incremental maintenance. Phase 1 (parallel over DISJOINT ASCENDING sequence
     * chunks): each thread traverses the trie per sequence, recording per-pattern max-measure utility;
     * it folds occurrences into O(1)-per-pattern accumulators (first/last/maxGap-within-chunk/utilSum).
     * Phase 2 (parallel over patterns): merge the T chunk accumulators in ascending thread order — the
     * cross-chunk gap is (next chunk's first occurrence − previous chunk's last), so regularity is exact
     * without materializing the full occurrence list. Result identical to the per-pattern re-match.
     */
    private void maintainTrie(int lo, int hi) {
        if (trieRoot == null || trieForPats != pats.length) buildTrie();
        final int nSeq = hi - lo;
        if (nSeq <= 0) return;
        final int P = pats.length;
        final int T = Math.max(1, Math.min(numThreads, nSeq));

        final boolean[][] occ = new boolean[T][];
        final int[][] first = new int[T][], last = new int[T][], maxGap = new int[T][];
        final long[][] uSum = new long[T][];

        runParallelIndexed(T, t -> {
            final int cLo = lo + (int) ((long) nSeq * t / T);
            final int cHi = lo + (int) ((long) nSeq * (t + 1) / T);
            final boolean[] oc = new boolean[P];
            final int[] fs = new int[P], ls = new int[P], mg = new int[P];
            final long[] us = new long[P];
            final TrieWs w = trieWs.get();
            for (int s = cLo; s < cHi; s++) {              // ascending within the chunk
                traverse(s, w);
                final IntLongHashMap best = w.best;
                for (int sl = 0; sl < best.slotCount(); sl++) {
                    if (!best.occupied(sl)) continue;
                    int pi = best.keyAt(sl); long u = best.valAt(sl);
                    if (!oc[pi]) { oc[pi] = true; fs[pi] = s; ls[pi] = s; us[pi] = u; }
                    else { int g = s - ls[pi]; if (g > mg[pi]) mg[pi] = g; ls[pi] = s; us[pi] += u; }
                }
            }
            occ[t] = oc; first[t] = fs; last[t] = ls; maxGap[t] = mg; uSum[t] = us;
        });

        runParallelIndexed(P, pi -> {
            Pat p = pats[pi];
            int prevLast = p.lastSeqId, maxP = p.maxInnerPeriod, newLast = p.lastSeqId;
            long add = 0; boolean any = false;
            for (int t = 0; t < T; t++) {
                if (!occ[t][pi]) continue;
                int f = first[t][pi], l = last[t][pi], g = maxGap[t][pi];
                int gapIn = (prevLast == -1) ? (f + 1) : (f - prevLast);   // cross-chunk (or first-ever) gap
                if (gapIn > maxP) maxP = gapIn;
                if (g > maxP) maxP = g;                                    // largest gap WITHIN the chunk
                prevLast = l; newLast = l; add += uSum[t][pi]; any = true;
            }
            if (any) { p.utility += add; p.maxInnerPeriod = maxP; p.lastSeqId = newLast; }
        });
    }

    /** Fill {@code w.best} = {patIdx -> exact max-measure utility of that pattern in sequence s}. */
    private void traverse(int s, TrieWs w) {
        w.best.clear(); w.memo.clear();
        final int[] eventItemStart = data.eventItemStart, seqEventStart = data.seqEventStart, items = data.items;
        final long[] utils = data.utils;
        final int firstEv = seqEventStart[s], evCount = seqEventStart[s + 1] - firstEv;
        if (trieRoot.sCh == null) return;
        for (int e = 0; e < evCount; e++) {                // a root s-child may match at ANY position
            int ge = firstEv + e, p0 = eventItemStart[ge], p1 = eventItemStart[ge + 1];
            for (int pos = p0; pos < p1; pos++) {
                TNode c = trieRoot.sCh.get(items[pos]);
                if (c != null) explore(c, e, pos - p0, utils[pos], w, firstEv, evCount);
            }
        }
    }

    /** DFS with dominance memo (exact max-measure): record best utility at each pattern node, extend
     *  by i-children (same event, later item) and s-children (later events). */
    private void explore(TNode node, int e, int j, long acc, TrieWs w, int firstEv, int evCount) {
        long key = ((long) node.id << 32) | ((long) e << 16) | j;
        if (w.memo.dominated(key, acc)) return;
        if (node.patIdx >= 0) w.best.putMax(node.patIdx, acc);
        final int[] eventItemStart = data.eventItemStart, items = data.items;
        final long[] utils = data.utils;
        final int ge = firstEv + e, p0 = eventItemStart[ge], p1 = eventItemStart[ge + 1];
        if (node.iCh != null) {                            // i-children: same event, item index > j
            for (int pos = p0 + j + 1; pos < p1; pos++) {
                TNode c = node.iCh.get(items[pos]);
                if (c != null) explore(c, e, pos - p0, acc + utils[pos], w, firstEv, evCount);
            }
        }
        if (node.sCh != null) {                            // s-children: later events, any item
            for (int e2 = e + 1; e2 < evCount; e2++) {
                int ge2 = firstEv + e2, q0 = eventItemStart[ge2], q1 = eventItemStart[ge2 + 1];
                for (int pos = q0; pos < q1; pos++) {
                    TNode c = node.sCh.get(items[pos]);
                    if (c != null) explore(c, e2, pos - q0, acc + utils[pos], w, firstEv, evCount);
                }
            }
        }
    }

    /**
     * Inverted index over new sequences {@code [lo,hi)}: item -> ascending array of seqIds that contain
     * the item. Two passes (count, then fill) so each posting list is exactly sized and in ascending
     * seqId order. Keyed by item -> at most |alphabet| entries, independent of batch size.
     */
    private Map<Integer, int[]> buildInvertedIndex(int lo, int hi) {
        final int[] items = data.items, eventItemStart = data.eventItemStart, seqEventStart = data.seqEventStart;
        Map<Integer, Integer> count = new java.util.HashMap<>();
        IntHashSet seen = new IntHashSet(64);
        for (int s = lo; s < hi; s++) {
            distinctItems(s, items, eventItemStart, seqEventStart, seen);
            for (int z : seen.toArray()) count.merge(z, 1, Integer::sum);
        }
        Map<Integer, int[]> post = new java.util.HashMap<>(count.size() * 2);
        Map<Integer, Integer> fill = new java.util.HashMap<>(count.size() * 2);
        for (Map.Entry<Integer, Integer> e : count.entrySet()) post.put(e.getKey(), new int[e.getValue()]);
        for (int s = lo; s < hi; s++) {                    // ascending s -> ascending posting lists
            distinctItems(s, items, eventItemStart, seqEventStart, seen);
            for (int z : seen.toArray()) {
                int f = fill.getOrDefault(z, 0);
                post.get(z)[f] = s;
                fill.put(z, f + 1);
            }
        }
        return post;
    }

    /** Collect the distinct items of sequence {@code s} into the reused set {@code out}. */
    private static void distinctItems(int s, int[] items, int[] eventItemStart, int[] seqEventStart, IntHashSet out) {
        out.clear();
        int firstEv = seqEventStart[s], lastEv = seqEventStart[s + 1];
        for (int ge = firstEv; ge < lastEv; ge++)
            for (int pos = eventItemStart[ge]; pos < eventItemStart[ge + 1]; pos++)
                out.add(items[pos]);
    }

    /**
     * Candidate sequences for pattern {@code p} = INTERSECTION of the posting lists of its distinct
     * items (sequences containing EVERY pattern item — the tightest order-free necessary condition).
     * Returns null if any item is absent from the batch. Iterates the shortest list and keeps entries
     * present in all others (binary search); the result stays in ascending seqId order.
     */
    private int[] candidateSeqs(Pat p, Map<Integer, int[]> inv) {
        int[][] lists = new int[p.item.length][];
        int m = 0;
        outer:
        for (int k = 0; k < p.item.length; k++) {
            int[] lst = inv.get(p.item[k]);
            if (lst == null) return null;                  // required item absent from the whole batch
            for (int j = 0; j < m; j++) if (lists[j] == lst) continue outer;   // duplicate item -> same list
            lists[m++] = lst;
        }
        int shortest = 0;
        for (int j = 1; j < m; j++) if (lists[j].length < lists[shortest].length) shortest = j;
        int[] base = lists[shortest];
        if (m == 1) return base;
        int[] out = new int[base.length];
        int n = 0;
        for (int s : base) {
            boolean all = true;
            for (int j = 0; j < m; j++) {
                if (j == shortest) continue;
                if (java.util.Arrays.binarySearch(lists[j], s) < 0) { all = false; break; }
            }
            if (all) out[n++] = s;
        }
        return (n == base.length) ? base : java.util.Arrays.copyOf(out, n);
    }

    /**
     * Exact max-measure utility of pattern {@code (ext,item)} in sequence {@code seqId} via a
     * frontier-based dynamic program: at each step keep all non-dominated end positions (max utility
     * per position) rather than a single greedy state — matches the oracle semantics. Returns
     * {@link Long#MIN_VALUE} if absent.
     */
    private long matchPatternUtil(int[] ext, int[] item, int seqId) {
        if (COUNT) matchCalls.increment();
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
        if (cur.isEmpty()) { if (COUNT) matchMisses.increment(); return Long.MIN_VALUE; }
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
            if (nxt.isEmpty()) { if (COUNT) matchMisses.increment(); return Long.MIN_VALUE; }
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
        List<long[]> neighbors = (iExt ? enumDeucs.adjInEvent : enumDeucs.adjAfter).get(lastItem);
        if (neighbors == null) return EMPTY_INT;
        IntHashSet promising = w.promising; promising.clear();   // reuse (no per-node allocation)
        for (long[] ny : neighbors)
            if (ny[1] >= enumThreshold) promising.add((int) ny[0]);
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
        for (int z : prom) if (lapeu.get(z, 0L) >= enumThreshold) prom[cnt++] = z;
        int[] cand = java.util.Arrays.copyOf(prom, cnt);
        java.util.Arrays.sort(cand);
        return cand;
    }

    // =====================================================================
    //  LAZY BUFFER — sound dormancy bound + exact catch-up
    // =====================================================================

    /**
     * One pass over ΔD filling the three per-batch bound tables:
     * SWU (item -> Σ u(S) over sequences containing it), IU (item -> Σ occurrence utilities —
     * u(α,S) picks ONE occurrence per pattern item, so window gain ≤ Σ_k IU_win(item_k)), and
     * u(S) per sequence (tier-2: Σ u(S) over the posting-list INTERSECTION of the pattern's items).
     */
    private void computeLazyBatchStats(List<List<int[]>> deltaD, int lo) {
        Map<Integer, Long> swu = new java.util.HashMap<>();
        Map<Integer, Long> iu = new java.util.HashMap<>();
        long[] su = new long[deltaD.size()];
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < deltaD.size(); i++) {
            List<int[]> S = deltaD.get(i);
            long uS = 0;
            for (int[] ev : S) for (int k = 1; k < ev.length; k += 2) uS += ev[k];
            su[i] = uS;
            seen.clear();
            for (int[] ev : S)
                for (int j = 0; j + 1 < ev.length; j += 2) {
                    iu.merge(ev[j], (long) ev[j + 1], Long::sum);
                    if (seen.add(ev[j])) swu.merge(ev[j], uS, Long::sum);
                }
        }
        swuByBatch.put(batchIdx, swu);
        iuByBatch.put(batchIdx, iu);
        utilByBatch.put(batchIdx, su);
        loByBatch.put(batchIdx, lo);
    }

    /**
     * For every cold pattern: certify it cannot influence the HS output, or catch it up EXACTLY.
     * Window-gain bound = min( min_k SWU_win(item_k),  Σ_k IU_win(item_k) ) — both sound:
     * the pattern occurs in a sequence ⇒ every item occurs there (SWU term), and u(α,S) picks one
     * occurrence per pattern item (IU term). Wake threshold is {@code minUtil}, NOT θ(t): a cold
     * pattern certified below minUtil cannot enter HS, which is the exactness that matters — its SHS
     * membership may go stale (diagnostic only). Woken patterns are caught up over precisely the
     * skipped batches (stored posting lists, ascending seqIds ⇒ gap bookkeeping stays correct) and
     * reactivated BEFORE classify, so same-batch promotion works — per-batch HS stays exact.
     */
    private void wakeUncertainCold(boolean forceAll) {
        final Pat[] arr = pats;
        final long wakeAt = minUtil;                              // HS-exactness threshold
        final int curBatch = batchIdx;
        final java.util.concurrent.atomic.AtomicLong skips = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong t2skips = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong wakes = new java.util.concurrent.atomic.AtomicLong();
        runParallelIndexed(arr.length, idx -> {
            Pat p = arr[idx];
            if (p.active || p.coldFromBatch < 0) return;          // hot, or hard-evicted (non-lazy path)
            if (forceAll) {                                       // memory checkpoint: catch everyone up
                for (int b = p.coldFromBatch; b <= curBatch; b++) {
                    int[] cand = candidateSeqs(p, invByBatch.get(b));
                    if (cand != null) for (int s : cand) matchAndUpdate(p, s);
                }
                p.active = true; p.coldFromBatch = -1;
                wakes.incrementAndGet();
                return;
            }
            long swuMin = Long.MAX_VALUE;                         // min over items of window SWU
            long iuSum = 0;                                       // Σ over items of window item-utility
            for (int k = 0; k < p.item.length; k++) {
                long s = 0, u = 0;
                for (int b = p.coldFromBatch; b <= curBatch; b++) {
                    Map<Integer, Long> swu = swuByBatch.get(b);
                    if (swu != null) { Long v = swu.get(p.item[k]); if (v != null) s += v; }
                    Map<Integer, Long> iu = iuByBatch.get(b);
                    if (iu != null) { Long v = iu.get(p.item[k]); if (v != null) u += v; }
                }
                if (s < swuMin) swuMin = s;
                iuSum += u;
            }
            long bound1 = Math.min(swuMin, iuSum);
            if (p.utility + bound1 < wakeAt) { skips.incrementAndGet(); return; }  // tier-1: certified out of HS reach
            // Tier-2: fold the not-yet-seen window batches into the intersection bound
            // Σ u(S) over sequences containing ALL pattern items (incremental via t2Sum/t2Batch —
            // each batch is intersected at most once per cold stretch).
            for (int b = Math.max(p.coldFromBatch, p.t2Batch + 1); b <= curBatch; b++) {
                int[] cand = candidateSeqs(p, invByBatch.get(b));
                if (cand != null) {
                    long[] su = utilByBatch.get(b);
                    int lo = loByBatch.get(b);
                    for (int s : cand) p.t2Sum += su[s - lo];
                }
            }
            p.t2Batch = curBatch;
            if (p.utility + Math.min(bound1, p.t2Sum) < wakeAt) { t2skips.incrementAndGet(); return; }
            for (int b = p.coldFromBatch; b <= curBatch; b++) {   // exact catch-up over the skipped window
                int[] cand = candidateSeqs(p, invByBatch.get(b));
                if (cand != null) for (int s : cand) matchAndUpdate(p, s);
            }
            p.active = true; p.coldFromBatch = -1;
            wakes.incrementAndGet();
        });
        lazyBoundSkips += skips.get();
        lazyT2Skips += t2skips.get();
        lazyWakeups += wakes.get();
        coldCount -= (int) wakes.get();
    }

    /** Drop ALL per-batch lazy tables (valid only right after a force-wake — no cold pattern left). */
    private void clearLazyBookkeeping() {
        swuByBatch.clear(); iuByBatch.clear(); invByBatch.clear();
        utilByBatch.clear(); loByBatch.clear();
        postingInts = 0;
        lazyCheckpoints++;
    }

    /** Human-readable lazy bookkeeping footprint (test.LazySmoke). */
    public String lazyFootprint() {
        long utilRows = 0;
        for (long[] a : utilByBatch.values()) utilRows += a.length;
        long swuEntries = 0;
        for (Map<Integer, Long> m : swuByBatch.values()) swuEntries += m.size();
        return String.format("batches=%d postings=%,d ints (~%.1f MB) utilRows=%,d swuEntries=%,d checkpoints=%d",
                invByBatch.size(), postingInts, postingInts * 4 / 1048576.0, utilRows, swuEntries, lazyCheckpoints);
    }

    // =====================================================================
    //  DISCOVERY (re-seed) — enumerate ΔD, verify exactly over the full DB
    // =====================================================================

    /**
     * Discovery pass on the new batch: (1) enumerate ΔD at the batch-scaled threshold
     * θ_disc = κ·θ(t)·(batchUtil/totalUtil) — a pattern "on pace" for SHS overall carries at least
     * that much utility inside ΔD; (2) verify each candidate EXACTLY over the full DB
     * (utility + inner periods via {@link #matchPatternUtil}); accept at θ(t) as a new tracked
     * pattern, or resurrect an evicted one with freshly recomputed state. Regularity is NOT pruned
     * during the ΔD enumeration (batch-local periods are meaningless); the verification step applies
     * the anti-monotone check maxInnerPeriod ≤ maxReg instead.
     */
    private void discover(List<List<int[]>> deltaD, int lo, int hi, long batchUtil) {
        double share = (double) batchUtil / data.totalDbUtility;
        double thDisc = Math.max(1.0, reseedScale * bufferThreshold * share);
        DEUCS local = new DEUCS();                     // ΔD-local candidate-generation structure
        local.incUpdate(deltaD);
        local.buildAdjacency();
        // Window enumeration: prune on INTRA-window gaps (sound — a gap between consecutive occurrences
        // inside [lo,hi) is a true global gap), cap the candidate count as an OOM safety valve.
        enumDeucs = local; enumThreshold = thDisc; enumMaxReg = maxReg;
        enumUseIntraGap = true; enumCap = reseedCandidateCap; enumCount.set(0);
        try {
            enumerateRange(lo, hi);
        } catch (CapExceeded ce) {
            patsQueue.clear();
            discAborted++;                             // batch gets no discovery (all-or-nothing)
            return;
        }
        List<Pat> cands = new ArrayList<>(patsQueue);  // ΔD-local stats only — recomputed below
        patsQueue.clear();
        if (cands.isEmpty()) return;
        discCandidates += cands.size();

        Map<Integer, int[]> inv = buildInvertedIndex(0, data.numSequences);
        ConcurrentLinkedQueue<Pat> accepted = new ConcurrentLinkedQueue<>();
        LongAdder resurrected = new LongAdder();
        final double th = bufferThreshold; final int mr = maxReg;
        runParallelIndexed(cands.size(), ci -> {
            Pat c = cands.get(ci);
            Pat existing = patIndex.get(formatPattern(c.ext, c.item));
            if (existing != null && existing.active) return;      // already tracked & maintained
            int[] seqs = candidateSeqs(c, inv);
            if (seqs == null) return;
            long util = 0; int last = -1, maxP = 0;
            for (int s : seqs) {
                long u = matchPatternUtil(c.ext, c.item, s);
                if (u != Long.MIN_VALUE) {
                    int gap = (last == -1) ? (s + 1) : (s - last);
                    if (gap > maxP) maxP = gap;
                    util += u; last = s;
                }
            }
            if (last == -1 || util < th || maxP > mr) return;     // below buffer OR can never be regular
            if (existing != null) {                               // resurrect with exact recomputed state
                existing.utility = util; existing.lastSeqId = last; existing.maxInnerPeriod = maxP;
                existing.prevRatio = 0; existing.active = true;
                resurrected.increment();
            } else {
                accepted.add(new Pat(c.ext, c.item, util, last, maxP));
            }
        });
        discResurrected += resurrected.intValue();
        if (!accepted.isEmpty()) {
            List<Pat> add = new ArrayList<>(accepted);
            discAccepted += add.size();
            Pat[] np = java.util.Arrays.copyOf(pats, pats.length + add.size());
            for (int i = 0; i < add.size(); i++) {
                Pat p = add.get(i);
                np[pats.length + i] = p;
                patIndex.put(formatPattern(p.ext, p.item), p);
            }
            pats = np;
        }
    }

    // =====================================================================
    //  CLASSIFICATION — promote SHS->HS
    // =====================================================================
    private void classify() {
        highUtility.clear();
        bufferedN = 0;
        final int n = data.numSequences;
        // SHS floor for the diagnostic count: the seeding band lower edge μ_min·minUtil — identical
        // semantics to the pre-lazy suite (whose θ was always μ_min·minUtil), so shs_count stays
        // comparable across runs even though the lazy hot/cold boundary θ(t) may sit at minUtil.
        final double shsFloor = lazy ? buffer.bufferFactorMin * minUtil : bufferThreshold;
        for (Pat p : pats) {
            boolean cold = !p.active && p.coldFromBatch >= 0;    // frozen (lazy) — RETAINED, counts as buffered
            if (!p.active && !cold) continue;                    // hard-evicted (legacy path) — gone for good
            if (p.lastSeqId == -1) continue;
            if (p.active) {
                double ratio = (double) p.utility / minUtil;
                if (freezeAllowed && p.utility < bufferThreshold) {  // lazy: below θ -> COLD (exactly recoverable)
                    p.active = false; p.coldFromBatch = batchIdx + 1;  // first batch the frozen state will not see
                    p.t2Sum = 0; p.t2Batch = batchIdx;           // fresh tier-2 accumulator for this cold stretch
                    p.prevRatio = ratio; cold = true; coldCount++;   // fall through: still counted as buffered below
                } else if (!lazy && evict && p.utility < bufferThreshold) {  // hard eviction (legacy ablation)...
                    boolean rising = trendSpare && ratio > p.prevRatio + trendEps;
                    if (!rising) { p.active = false; p.prevRatio = ratio; continue; }
                    p.prevRatio = ratio;
                } else {
                    p.prevRatio = ratio;
                }
            }
            int trueMaxPer = p.trueMaxPer(n);
            if (trueMaxPer > maxReg) continue;                  // irregular -> discard from output (Corollary 2)
            if (p.utility >= minUtil) {
                if (!cold)                                       // cold is certified < minUtil, can never sit here
                    highUtility.put(formatPattern(p.ext, p.item), new long[]{p.utility, trueMaxPer});
            }
            else if (p.utility >= shsFloor) bufferedN++;         // count only — do NOT materialize a String per SHS
        }
        lastBufferedN = bufferedN;                               // engagement signal for the next batch
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
    @Override public int bufferedCount() { return bufferedN; }
    @Override public double peakMemoryMB() { return peakMB; }
    public long getMinUtil() { return minUtil; }
    public int getMaxReg() { return maxReg; }
    public double getBufferThreshold() { return bufferThreshold; }
    public long getExploredNodes() { return exploredNodes.sum(); }
}
