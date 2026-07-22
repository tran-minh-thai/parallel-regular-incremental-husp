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
    /** A/B switch: {@code true} = inverted-index prune in maintain(); {@code false} = original cross-product. */
    public boolean useInvertedIndex = true;
    /**
     * Maintain strategy. {@code false} (default) = per-pattern re-match (each kept pattern DP-matched
     * against its candidate sequences). {@code true} = content-driven: build a prefix trie over the
     * kept patterns and, per new sequence, traverse the trie guided by the sequence's items —
     * matching a shared prefix once serves all patterns extending it, parallelized over disjoint
     * ascending sequence ranges. This is what the proposed miner uses.
     */
    public boolean trieMaintain = false;

    // Discovery stats: candidates enumerated over the increment, and those admitted to the result.
    public int discCandidates = 0, discAccepted = 0;

    /**
     * Which regularity threshold to prune with while seeding.
     * <ul>
     *   <li>{@code true} — ρ·N_final, the tightest bound that stays sound, so no pattern is lost.
     *       A gap inside {@code D_old} survives unchanged when later batches are appended, while the
     *       threshold itself grows with N, so a pattern can be irregular now and regular once the
     *       database is complete. Needs the final sequence count as a hint, and prunes little when
     *       {@code D_old} is a small fraction of the data. This is what the proposed configuration
     *       uses; see {@code ExpConfig.newProposed}.</li>
     *   <li>{@code false} (default) — ρ·N_current, as in the sequential predecessor. It prunes harder
     *       and so keeps low δ on dense data affordable, but it drops exactly those patterns that
     *       become regular later, which costs recall. Kept for the ablation in S10.</li>
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

    /**
     * Seed {@code D_old} with the companion study's parallel static engine
     * ({@link AlgoRHUSPMinerParallel}: CSR layout, EUCS and LA-PEU bounds, RDLB scheduler) rather than
     * this class's own VUL/DEUCS enumeration. That engine is several times faster on the same input,
     * and seeding accounts for most of the total runtime, so it is the natural layer to build on: the
     * external engine produces the seed and the incremental maintenance here takes over from it.
     * <p>
     * The engine runs in {@code seedMode} at the buffer threshold and returns, for each pattern, the
     * {@code lastSeqId} and {@code maxInnerPeriod} needed to keep accumulating regularity across later
     * batches. Its output matches the in-house enumeration — the semantics are the same.
     */
    public boolean seedWithEngine05 = false;

    /**
     * Recover the patterns seeding alone cannot reach, which is what makes the result exact rather
     * than merely high-coverage.
     * <p>
     * Incremental maintenance is cheap, but it can only promote patterns that were seeded in the first
     * place. A pattern whose utility in {@code D_old} falls below θ₀ is never tracked, so it stays
     * missing even when later batches push it past {@code minUtil}. With this flag set, querying the
     * result triggers a single reconciliation pass that recovers every such pattern.
     * <p>
     * That pass cannot miss one. A missed pattern α has {@code u(α,D_old) < θ₀} and
     * {@code u(α,D) ≥ minUtil}; utility is additive over the partition, so
     * {@code u(α,ΔD) = u(α,D) − u(α,D_old) > minUtil − θ₀}. Mining the increments at that threshold
     * therefore surfaces it.
     * <p>
     * The threshold is not a free parameter. μ = 1 gives θ₀ = δ·U(D_old) and hence a discovery
     * threshold of δ·U(D) − δ·U(D_old) = δ·U(ΔD), so each part is mined at its own natural threshold —
     * the condition the partition lemma in {@link #discoverFrom} needs. It is also the cost minimum in
     * practice: seeding grows cheaper as θ₀ rises while discovery grows dearer, and the two cross
     * there.
     */
    public boolean discoverExact = false;

    /**
     * Mine each batch Δ_k once at its own natural threshold δ·U(Δ_k), so the result is exact after
     * every batch rather than only when queried.
     *
     * <p>This is the same partition lemma applied to a finer partition
     * ({@code D = D_old ⊎ Δ₁ ⊎ … ⊎ Δ_k}). Weighed against {@link #discoverExact}, which uses the
     * coarsest two-part split:
     * <ul>
     *   <li>{@code discoverExact} mines the whole increment once and so produces the fewest
     *       candidates, but the answer is exact only at query time, and querying after every batch
     *       re-mines an increment that keeps growing.</li>
     *   <li>{@code partitionMine} mines each sequence exactly once, ever, and leaves the answer exact
     *       after every batch — but k parts mined at the same δ yield roughly k times the candidates
     *       of a single part, since a small part has a small absolute threshold and so admits patterns
     *       that are locally strong yet globally negligible.</li>
     * </ul>
     * Both are complete: coarser partitions cost less, finer ones give per-batch exactness.
     */
    public boolean partitionMine = false;

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
        // Never serialised; declared only because the supertype is Serializable.
        private static final long serialVersionUID = 1L;
        CapExceeded() { super(null, null, false, false); }
    }

    // ----- discoverExact state -----
    private final List<List<int[]>> increments = new ArrayList<>();   // raw ΔD (everything after D_old)
    private double seedThreshold0 = 0;                                // θ₀ actually used at seeding
    private boolean reconciled = false;
    public long discMs = 0;                                           // wall-clock spent in reconcile()

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
    // Instrumentation: incremental re-match calls + misses; guarded by COUNT so
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
        if (!seedWithEngine05) {          // DEUCS only serves the in-house enumeration
            deucs.incUpdate(dOld);
            deucs.buildAdjacency();      // adjacency index for fast candidate generation
        }
        data.appendBatch(dOld);
        recomputeThresholds(0, 0, dOld.size(), 0);   // first batch: μ = μ_min
        // Seeding-time regularity pruning threshold = ρ·N_final (if total N is known) — cut irregular
        // branches early without losing patterns regular-at-final-DB. No hint -> use current maxReg
        // (may prune slightly too aggressively).
        seedMaxReg = (seedPruneByFinalN && hintedTotalN > data.numSequences)
                ? (int) (maxRegRatio * hintedTotalN)     // sound (ρ·N_final) — no recall loss
                : maxReg;                                // default ρ·N_current — strong pruning, approximate (paper)
        seedThreshold0 = bufferThreshold;      // θ₀ — the discovery bound is derived from exactly this
        if (seedWithEngine05) seedWithParallelEngine(dOld);
        else staticBuild();
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
    /**
     * Seed with the companion study's parallel engine (see {@link #seedWithEngine05}). Runs it in
     * {@code seedMode} at the exact buffer threshold θ and the seeding regularity bound, then converts
     * each returned pattern into a {@link Pat} carrying the state needed to continue incrementally
     * (utility, lastSeqId, maxInnerPeriod).
     */
    private void seedWithParallelEngine(List<List<int[]>> dOld) {
        AlgoRHUSPMinerParallel eng = new AlgoRHUSPMinerParallel();
        eng.numThreads = numThreads;
        eng.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
        eng.denseBuffers = true;
        eng.useEUCS = true;
        eng.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
        eng.useRegPruning = true;
        eng.seedMode = true;                                     // keep seeds with a large trailing gap
        eng.forcedMinUtil = (long) Math.ceil(bufferThreshold);   // θ = μ_min · minUtil
        eng.forcedMaxReg = seedMaxReg;
        eng.runAlgorithmInMemory(dOld, minUtilRatio, maxRegRatio);

        for (Map.Entry<String, AlgoRHUSPMinerParallel.PatternResult> e : eng.finalPatterns.entrySet()) {
            AlgoRHUSPMinerParallel.PatternResult r = e.getValue();
            if (r.lastSeqId < 0) continue;
            int[][] path = parsePath(e.getKey());
            patsQueue.add(new Pat(path[0], path[1], r.utility, r.lastSeqId, r.maxInnerPeriod));
        }
    }

    /** Parse {@code "<(1 2)(3)>"} into {ext[], item[]}: first item of an event = S_EXT, later = I_EXT. */
    private static int[][] parsePath(String key) {
        String body = key.replace("<", "").replace(">", "").trim();
        String[] events = body.replace(")(", ")#(").split("#");
        List<Integer> exts = new ArrayList<>(), items = new ArrayList<>();
        for (String ev : events) {
            String inner = ev.replace("(", "").replace(")", "").trim();
            if (inner.isEmpty()) continue;
            boolean first = true;
            for (String tok : inner.split("[,\\s]+")) {
                if (tok.isEmpty()) continue;
                items.add(Integer.parseInt(tok));
                exts.add(first ? S_EXT : I_EXT);
                first = false;
            }
        }
        int n = items.size();
        int[] ext = new int[n], item = new int[n];
        for (int i = 0; i < n; i++) { ext[i] = exts.get(i); item[i] = items.get(i); }
        return new int[][]{ext, item};
    }

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
        // Never serialised; declared only because the supertype is Serializable.
        private static final long serialVersionUID = 1L;
        final int[] ext, item;
        final transient VerticalUtilityList vul;
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

        if (discoverExact) { increments.addAll(deltaD); reconciled = false; }   // ΔD kept raw; new data ⇒ must re-reconcile
        long prevUD = data.totalDbUtility; int prevN = data.numSequences;
        long batchUtil = AdaptiveBuffer.batchUtility(deltaD);
        int[] range = data.appendBatch(deltaD);        // append at tail -> [lo,hi); update totalDbUtility/numSequences
        recomputeThresholds(batchUtil, prevUD, deltaD.size(), prevN);

        maintain(range[0], range[1]);                  // re-match each pattern on new sequences (parallel over patterns)
        if (partitionMine) {                           // mine Δ_k ONCE at its own natural threshold δ·U(Δ_k)
            long t = System.currentTimeMillis();
            discoverFrom(deltaD, minUtilRatio * batchUtil);
            discMs += System.currentTimeMillis() - t;
        }
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
        final Map<Integer, int[]> inv = buildInvertedIndex(lo, hi);
        runParallelIndexed(arr.length, idx -> {
            Pat p = arr[idx];
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
    //  CLASSIFICATION — promote SHS->HS
    // =====================================================================
    private void classify() {
        highUtility.clear();
        bufferedN = 0;
        final int n = data.numSequences;
        for (Pat p : pats) {
            if (p.lastSeqId == -1) continue;
            int trueMaxPer = p.trueMaxPer(n);
            if (trueMaxPer > maxReg) continue;                  // irregular -> discard from output (Corollary 2)
            if (p.utility >= minUtil)
                highUtility.put(formatPattern(p.ext, p.item), new long[]{p.utility, trueMaxPer});
            else if (p.utility >= bufferThreshold) bufferedN++; // count only — do NOT materialize a String per SHS
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

    /** Querying the result triggers the reconciliation when {@link #discoverExact} is on (exact on demand). */
    @Override public Map<String, long[]> getHighUtilityPatterns() {
        if (discoverExact && !reconciled) {
            long t0 = System.currentTimeMillis();
            reconcile();
            discMs += System.currentTimeMillis() - t0;
            reconciled = true;
        }
        return highUtility;
    }

    /** Query-time reconciliation (2-part split): mine ALL increments at θ_disc = minUtil − θ₀. */
    private void reconcile() {
        if (increments.isEmpty()) return;
        discoverFrom(increments, minUtil - seedThreshold0);   // θ₀ = δ·U(D_old) ⟹ θ_disc = δ·U(ΔD)
    }

    /**
     * PARTITION-LEMMA DISCOVERY — the one mechanism that removes the seed-once ceiling.
     *
     * <p>For a sequence-disjoint partition {@code D = P₁ ⊎ … ⊎ P_m}, if {@code u(α,Pᵢ) < δ·U(Pᵢ)} for
     * every i then {@code u(α,D) = Σ u(α,Pᵢ) < δ·Σ U(Pᵢ) = minUtil}. Contrapositive: every globally
     * high-utility α clears the NATURAL threshold {@code δ·U(Pᵢ)} in at least one part. So mining each
     * part at its own natural threshold yields a COMPLETE candidate superset.
     *
     * <p>This method mines one part and folds its candidates into the tracked set. Callers:
     * <ul>
     *   <li>{@link #reconcile()} — part = all increments, threshold = minUtil − θ₀ (coarse 2-part split:
     *       fewest candidates, but exact only at query time);</li>
     *   <li>{@link #partitionMine} — part = Δ_k, threshold = δ·U(Δ_k) (fine k-part split: each sequence
     *       mined exactly once, ever, and the answer is exact after EVERY batch).</li>
     * </ul>
     *
     * <p>Every fresh candidate is KEPT, not just the currently-qualifying ones: the lemma only promises
     * that α surfaces in SOME part, possibly a past one, so a candidate that is below minUtil now may be
     * the one that crosses it three batches later. Dropping it would re-open the ceiling.
     */
    private void discoverFrom(List<List<int[]>> part, double threshold) {
        if (part.isEmpty() || threshold <= 0) return;          // θ ≥ minUtil ⟹ nothing can be missed

        AlgoRHUSPMinerParallel eng = new AlgoRHUSPMinerParallel();
        eng.numThreads = numThreads;
        eng.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
        eng.denseBuffers = true;
        eng.useEUCS = true;
        eng.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
        eng.useRegPruning = true;
        eng.windowMode = true;                                 // a mid-stream part: neither leading nor
                                                               // trailing gap is a real gap of α in D
        eng.forcedMinUtil = (long) Math.ceil(threshold);
        eng.forcedMaxReg = seedMaxReg;                         // ρ·N_final — sound for FUTURE batches too
        eng.runAlgorithmInMemory(part, minUtilRatio, maxRegRatio);
        discCandidates += eng.finalPatterns.size();
        if (eng.finalPatterns.isEmpty()) return;

        java.util.HashSet<String> known = new java.util.HashSet<>(pats.length * 2);
        for (Pat p : pats) known.add(formatPattern(p.ext, p.item));
        final List<Pat> fresh = new ArrayList<>();
        for (String key : eng.finalPatterns.keySet()) {
            if (known.contains(key)) continue;
            int[][] path = parsePath(key);
            if (path[1].length == 0) continue;
            fresh.add(new Pat(path[0], path[1], 0, -1, 0));   // exact state recomputed below
        }
        if (fresh.isEmpty()) return;

        // Exact full-DB state for each fresh candidate, from scratch over [0, n).
        final int n = data.numSequences;
        final Map<Integer, int[]> inv = buildInvertedIndex(0, n);
        runParallelIndexed(fresh.size(), i -> {
            Pat p = fresh.get(i);
            int[] cand = candidateSeqs(p, inv);
            if (cand == null) return;
            for (int s : cand) matchAndUpdate(p, s);           // ascending seqIds -> regularity is exact
        });

        for (Pat p : fresh) {
            if (p.lastSeqId == -1 || p.utility < minUtil) continue;
            int trueMaxPer = p.trueMaxPer(n);
            if (trueMaxPer > maxReg) continue;
            highUtility.put(formatPattern(p.ext, p.item), new long[]{p.utility, trueMaxPer});
            discAccepted++;
        }
        // KEEP ALL of them — see the javadoc above.
        Pat[] np = java.util.Arrays.copyOf(pats, pats.length + fresh.size());
        for (int i = 0; i < fresh.size(); i++) np[pats.length + i] = fresh.get(i);
        pats = np;
        trieRoot = null;                                        // force a trie rebuild
    }
    @Override public int bufferedCount() { return bufferedN; }
    @Override public long discoveryMs() { return discMs; }
    @Override public double peakMemoryMB() { return peakMB; }
    public long getMinUtil() { return minUtil; }
    public int getMaxReg() { return maxReg; }
    public double getBufferThreshold() { return bufferThreshold; }
    public long getExploredNodes() { return exploredNodes.sum(); }
}
