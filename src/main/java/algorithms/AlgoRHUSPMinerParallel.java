package algorithms;

import java.io.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Parallel engine for Regular High Utility Sequential Pattern (RHUSP) mining on shared-memory
 * multi-core hardware -- the proposed architecture described in the paper.
 *
 * The engine shares all pruning math with the sequential counterpart {@link AlgoRHUSPMiner}
 * (EUCS + PEU/LA-PEU upper bounds + regularity constraint), so it produces exactly the same
 * RHUSP set regardless of the thread count. The distinction lies in execution strategy:
 *
 *   Phase 1 (preprocessing) -- PARALLEL: SWU/maxPeriod scan and EUCS co-occurrence counting
 *   split across T thread slices; per-slice results merged via reduction. (Phase 1)
 *
 *   Phase 2 (mining) -- PARALLEL: the prefix-enumeration tree is traversed by a
 *   {@link ForkJoinPool} in which each thread owns a local deque and pushes/pops in LIFO
 *   order for cache locality. Work stealing from the tail of overloaded deques provides
 *   automatic load balancing. Recursive dynamic load balancing (RDLB) is achieved by
 *   conditionally forking a subtask only when the pool has surplus capacity
 *   (getSurplusQueuedTaskCount), and recursing inline otherwise to retain locality.
 *
 *   Hardware-friendly layout: the immutable CSR-layout sequence database is shared across
 *   all threads. Per-task hot buffers (frequency counters, bound accumulators, candidate
 *   lists, pattern buffer, LA-stamp array) are managed through a
 *   {@link ConcurrentLinkedQueue} pool (NOT ThreadLocal -- see the contextPool field for
 *   the rationale) to eliminate lock contention and reduce false sharing.
 *
 * Correctness: the result set is independent of thread count and matches the sequential
 * engine (verified by test.StrategyVerificationTest).
 */
public class AlgoRHUSPMinerParallel {

    public static final int BOUND_NONE = 0;
    public static final int BOUND_PEU = 1;
    public static final int BOUND_LA_PEU = 2;

    // ----- Configuration -----
    public boolean useEUCS = true;
    public int boundMode = BOUND_LA_PEU;
    public boolean useRegPruning = true;
    public int numThreads = Runtime.getRuntime().availableProcessors();
    public String label = "RHUSPM_Par";

    // Fork threshold: only fork when the local surplus task count is <= this value -- keeps
    // all threads busy without exploding the number of tiny tasks (RDLB).
    public int forkSurplusThreshold = 3;
    // Granularity threshold: only fork when the projected database is large enough; small
    // projections recurse inline to avoid spawning micro-tasks (each task borrows a context
    // holding many arrays, so forking small branches is expensive).
    public int minForkInstances = 64;

    // ----- Parallel-technique ABLATION switches (for experiment SC3) -----
    // Load-balancing strategy:
    //   STRAT_STATIC : static partition -- divide level-0 candidates into T blocks, each block
    //                  mined inline by one thread (no stealing, no dynamic splitting).
    //   STRAT_STEAL  : thread pool + work stealing at the root level only (each level-1 branch
    //                  is a task); deeper recursion runs inline (no dynamic splitting).
    //   STRAT_RDLB   : full -- work stealing + recursive dynamic load balancing (fork deeper
    //                  branches when pool has surplus capacity).
    public static final int STRAT_STATIC = 0, STRAT_STEAL = 1, STRAT_RDLB = 2;
    public int parallelStrategy = STRAT_RDLB;
    // Hardware-friendly buffer layout: true = buffers indexed by DENSE id (numValid); false =
    // by raw item id (maxItemId+1). Toggle to measure the contribution of the compact layout.
    public boolean denseBuffers = true;

    // ----- Statistics (thread-safe via LongAdder) -----
    public long startTimestamp, endTimestamp, preprocessMs, maxMemory;
    public long cpuStartNanos, cpuEndNanos;
    private final LongAdder exploredNodesA = new LongAdder();
    private final LongAdder candidateCountA = new LongAdder();
    private final LongAdder prunedEucsA = new LongAdder();
    private final LongAdder prunedBoundA = new LongAdder();
    private final LongAdder prunedNodeCondA = new LongAdder();
    private final LongAdder prunedRegularityA = new LongAdder();
    public long exploredNodes, candidateCount;
    public long prunedEucs, prunedBound, prunedNodeCond, prunedRegularity;

    public final Map<String, PatternResult> finalPatterns = new ConcurrentHashMap<>();

    // Cooperative timeout: ForkJoinPool worker threads are not reached by future.cancel(true)
    // (cancel only interrupts the submitting thread), so every worker checks the deadline at
    // checkMemory() (called once per node) and throws TIMEOUT_INTERRUPT when it expires --
    // the exception propagates out of pool.invoke(). timeoutMs <= 0 means no limit.
    public long timeoutMs = -1;
    /** Absolute deadline (epoch ms) overriding {@link #timeoutMs}, so a caller's budget also covers loading. */
    public long deadlineEpochMillis = -1;
    /** Run the GC-based peak-memory reset before mining; off on latency-critical paths (it sleeps). */
    public boolean measureMemory = true;
    private volatile long deadlineMillis = Long.MAX_VALUE;

    /**
     * Cooperative heap valve, mirroring the timeout above: 0 (default) is off; when set, every
     * worker compares the used heap against it once per mining node and aborts the whole run by
     * throwing {@link HeapBudgetExceeded}, which leaves through the same path the timeout uses.
     * The point is to stop ALLOCATING once the budget is breached, not merely to notice afterwards
     * -- an enumeration that only measures its peak on the way out has already paid it. When off,
     * the cost is one field read and a branch per node, so measured configurations are unaffected.
     */
    public long abortHeapBytes = 0;

    /**
     * Judge the valve on the live heap instead of the raw reading. totalMemory - freeMemory counts
     * garbage not yet collected, so a run that starts behind a large dead structure (the previous
     * full re-mine, say) can breach the valve before it has allocated anything of its own. When set,
     * a breach makes one worker collect (the others wait on the lock and re-read), and the run aborts
     * only if the heap is still above the valve after the collection. The collection time is part of
     * the run's own time, so a deadline still bounds a run that keeps breaching.
     */
    public boolean abortHeapLive = false;
    /** Collections the live valve has forced in this run. */
    public final java.util.concurrent.atomic.AtomicInteger heapValveCollections = new java.util.concurrent.atomic.AtomicInteger();
    /** Test hook: 1 = the live valve skips its collection and judges the raw reading; 2 = it never re-reads after collecting. */
    public static int HEAP_VALVE_FAULT = Integer.getInteger("heapvalve.fault", 0);
    private static final Object HEAP_VALVE_LOCK = new Object();

    /** Thrown from a worker when {@link #abortHeapBytes} is breached; caller tightens and retries. */
    public static final class HeapBudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        HeapBudgetExceeded() { super(null, null, false, false); }
    }

    /** The heap valve: returns when the heap is within {@link #abortHeapBytes} (or the valve is off), else throws. */
    public void checkHeapValve() {
        if (abortHeapBytes <= 0) return;
        Runtime rt = Runtime.getRuntime();
        if (rt.totalMemory() - rt.freeMemory() <= abortHeapBytes) return;
        if (!abortHeapLive || HEAP_VALVE_FAULT == 1) throw new HeapBudgetExceeded();
        synchronized (HEAP_VALVE_LOCK) {
            if (rt.totalMemory() - rt.freeMemory() <= abortHeapBytes) return;     // another worker has collected
            System.gc();
            heapValveCollections.incrementAndGet();
            if (HEAP_VALVE_FAULT != 2 && rt.totalMemory() - rt.freeMemory() > abortHeapBytes) throw new HeapBudgetExceeded();
        }
    }

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private volatile boolean samplerRunning = false;
    private Thread memorySampler;

    public static final class PatternResult {
        public final long utility;
        public final int periodicity;
        /** ADDED (incremental seeding): the last sequence in which the pattern occurs. */
        public final int lastSeqId;
        /** ADDED (incremental seeding): the largest gap among occurrences EXCLUDING the trailing gap,
         *  together with {@link #lastSeqId} this is exactly the state needed to CONTINUE the regularity
         *  accumulation when later batches arrive. */
        public final int maxInnerPeriod;
        PatternResult(long u, int p) { this(u, p, -1, 0); }
        PatternResult(long u, int p, int lastSeqId, int maxInnerPeriod) {
            utility = u; periodicity = p;
            this.lastSeqId = lastSeqId; this.maxInnerPeriod = maxInnerPeriod;
        }
    }

    // ----- CSR-layout flat arrays (immutable, shared across all threads) -----
    private int numSeq;
    private int[] seqPosStart;
    private int[] items;
    private long[] utils;
    private long[] remUtils;
    private int[] posEvent;
    private int[] seqEvtStart;
    private int[] evtPosStart;
    private long[] seqUtility;
    private int maxItemId, maxEventsPerSeq;
    private long totalDBUtility;

    private long minUtility;
    private int maxRegularity;

    public long forcedMinUtil = -1;
    public int forcedMaxReg = -1;

    /**
     * ADDED: INCREMENTAL SEEDING MODE. When mining {@code D_old} to seed an incremental miner, the
     * TRAILING-gap test ({@code numSeq - lastSeqId <= maxRegularity}) must NOT be applied: a pattern
     * whose last occurrence sits far from the end of {@code D_old} may still become regular once later
     * batches arrive (a new occurrence shrinks that gap). Applying it here would silently drop such
     * seeds and cost recall. The INNER-gap prune stays active, but not because inner gaps are
     * monotone under extension. They are not: a pattern at SIDs {1,10} with N=20 has an inner gap
     * of 9, while an extension surviving only at {1} has an inner gap of 1, because dropping the
     * last occurrence turns an inner gap into a trailing one. The prune is safe for a different
     * reason: maxPer (trailing gap included) IS anti-monotone, and inner gap ≤ maxPer, so an inner
     * gap already past the bound puts maxPer past it too, for the pattern and for every extension.
     */
    public boolean seedMode = false;

    /**
     * ADDED: WINDOW MODE (discovery). The database handed in is a WINDOW {@code ΔD} that does not
     * start at global sequence 0, so BOTH boundary gaps are meaningless here: the leading gap is
     * measured from the window's own first sequence (not the true predecessor in {@code D_old}), and
     * the trailing gap can still shrink. Only gaps between CONSECUTIVE occurrences INSIDE the window
     * are true global gaps, and only those may prune (they are anti-monotone). Implies the
     * {@link #seedMode} relaxations.
     */
    public boolean windowMode = false;

    /**
     * The window is a contiguous SUFFIX of the database: it starts right after an anchor prefix and
     * ends at the last sequence. Two boundary quantities then become usable in {@link #windowMode}:
     * <ul>
     *   <li>the leading gap measured from the window's start (first local SID + 1) is a LOWER BOUND of
     *       the true gap, because the previous occurrence, if any, lies before the window; extensions
     *       start no earlier, so the bound is anti-monotone and pruning on it is sound;</li>
     *   <li>the trailing gap is the true trailing gap, so the floor prune may also filter items.</li>
     * </ul>
     * Without the leading bound, a pattern confined to the last {@code maxRegularity} sequences of a
     * small window cannot be pruned on regularity at all, which is where small windows explode.
     */
    public boolean windowIsSuffix = false;

    /**
     * FLOOR prune for the relaxed modes. The trailing gap as a component can shrink -- a recurrence
     * resets it -- which is why the relaxed modes switch the trailing-gap prune off. But the floor
     * max(inner, trailing) cannot shrink: the same recurrence crystallizes an inner gap at least as
     * long as the trailing gap it replaces, and with no recurrence the trailing gap only grows. So
     * when the caller's bound is a ceiling on every future test (rho * N_final, an absolute declared
     * bound, or a declared completeness class), a node whose floor exceeds it is out in every future, and its
     * subtree with it -- extensions only lose occurrences, so the floor only grows. This is the
     * strongest prune the engine has, and the relaxed modes previously ran with it off entirely,
     * which is where their memory went. Off by default; the caller asserts the bound's validity.
     */
    public boolean floorPrune = false;

    // ----- Tier 0 / EUCS (immutable after Phase 1) -----
    private int[] itemToDense;
    private int[] denseToItem;
    private int numValid;
    private int[] adjIStart, adjSStart;
    private int[] adjIItem, adjSItem;
    private long[] adjIUtil, adjSUtil;

    // EUCS memory footprint (for contribution analysis: pair counts; I-pairs are stored bidirectionally).
    public long eucsPairsI, eucsPairsS;

    /**
     * Compact co-occurrence structure. When on: (1) the i-extension adjacency of item a keeps only items
     * b ordered after a, the only ones an i-extension of a pattern ending in a can append (items within an
     * event are sorted, and the extension region starts after a's position); (2) pairs whose accumulated
     * utility is below minUtility are dropped when the lists are built, since every lookup stops at them;
     * (3) the per-entry utility is no longer stored, because every kept entry passes the test. The look-ahead
     * stamp is built from the same lists, so the promising set shrinks to the items that can follow the last
     * item, which keeps the bound sound and makes it no looser than before.
     */
    public boolean compactEucs = !"false".equals(System.getProperty("eucs.compact"));
    /**
     * Deliberate defects for the audit only: 1 drops every i-pair, 2 stores i-pairs backward (hash
     * construction); 3 stores s-pairs backward (dense); 4 loses one key partition (partitioned); 5 lets the
     * sketch estimate fall below the true value (sketch).
     */
    static final int EUCS_FAULT = Integer.getInteger("eucs.fault", 0);

    /** Construction of the co-occurrence structure; all return the same kept pair set. */
    public static final int EUCS_AUTO = 0, EUCS_HASH = 1, EUCS_PARTITIONED = 2, EUCS_DENSE = 3, EUCS_SKETCH = 4;
    public static final String[] EUCS_BUILD_NAMES = {"auto", "hash", "partitioned", "dense", "sketch"};
    public int eucsBuild = Math.max(0, Arrays.asList(EUCS_BUILD_NAMES).indexOf(System.getProperty("eucs.build", "auto")));
    public int eucsBuildUsed = -1;
    /** Memory limit for all per-thread dense matrices together (bytes). */
    public static final long EUCS_DENSE_BUDGET = 256L << 20;
    public long eucsSketchCells, eucsSketchDropped;
    public boolean eucsSketchRefused;
    /** Audit counters of the co-occurrence structure (filled by buildEUCSParallel). */
    public long eucsDistinctI, eucsDistinctS, eucsKeptI, eucsKeptS, eucsThreadMapSlots, eucsMergedSlots;
    public long eucsAdjBytes;
    private final LongAdder eucsVisitedA = new LongAdder();      // adjacency entries read while stamping and collecting
    private final LongAdder eucsDeadReverseA = new LongAdder();  // i-entries ordered before the last item that passed minUtility
    public long eucsVisited, eucsDeadReverse;
    public double getEucsApproxMB() {
        // each pair: 1 int item (4B) + 1 long util (8B); plus offset arrays (numValid+1 ints each).
        long bytes = (eucsPairsI + eucsPairsS) * 12L + (long) (numValid + 1) * 2 * 4L;
        return bytes / (1024.0 * 1024.0);
    }

    private static final int ROOT_STAMP = 1;

    private ForkJoinPool pool;

    // ============================ PER-TASK CONTEXT ============================

    /**
     * All hot buffers allocated per task. Physical isolation (separate arrays per context)
     * eliminates locks on the hot path and reduces false sharing because threads write to
     * disjoint memory regions.
     */
    private final class MiningContext {
        final long[] seqBoundI, seqBoundS;
        final int[] seqVisitI, seqVisitS;
        final int[] seqActI, seqActS;
        int seqNumI, seqNumS;
        final long[] gAccI, gAccS;
        final int[] gMarkI, gMarkS;
        final int[] gActI, gActS;
        int gNumI, gNumS;
        final long[] evtMaxUtil;
        final int[] evtMaxPos;
        final int[] candBufI, candBufS;
        final int[] laStampArr;
        int laStampCounter;
        int[] patBuf = new int[64];
        int patLen = 0;

        MiningContext() {
            // dense: size numValid; raw: maxItemId+1 (for buffer-layout ablation)
            int n = denseBuffers ? Math.max(numValid, 1) : (maxItemId + 1);
            seqBoundI = new long[n]; seqBoundS = new long[n];
            seqVisitI = new int[n]; Arrays.fill(seqVisitI, -1);
            seqVisitS = new int[n]; Arrays.fill(seqVisitS, -1);
            seqActI = new int[n]; seqActS = new int[n];
            gAccI = new long[n]; gAccS = new long[n];
            gMarkI = new int[n]; Arrays.fill(gMarkI, -1);
            gMarkS = new int[n]; Arrays.fill(gMarkS, -1);
            gActI = new int[n]; gActS = new int[n];
            candBufI = new int[n]; candBufS = new int[n];
            laStampArr = new int[n];
            evtMaxUtil = new long[Math.max(maxEventsPerSeq, 1)];
            evtMaxPos = new int[Math.max(maxEventsPerSeq, 1)];
            laStampCounter = ROOT_STAMP;
            // Root promising set (Tier 0): stamp all valid items at ROOT_STAMP
            if (boundMode == BOUND_LA_PEU)
                for (int d = 0; d < numValid; d++) laStampArr[denseBuffers ? d : denseToItem[d]] = ROOT_STAMP;
        }
    }

    // CONTEXT POOL (NOT ThreadLocal). Rationale: when a ForkJoinPool thread calls join(),
    // the runtime may execute a stolen task on the WAITING thread to keep it productive
    // (join-helping). If context were ThreadLocal, that re-entrant task would overwrite the
    // buffers (patLen, gAcc, candBuf, ...) that the outer frame is still using -- silent
    // state corruption. Instead, each task borrows a context at compute() entry and returns
    // it on exit; inline recursion within the same task reuses the borrowed context.
    // Invariant: a context is clean after each mine()/processOne() call (seqVisit=-1,
    // gMark=-1, counts=0, push/pop balanced), so a returned context is always ready to reuse.
    private final ConcurrentLinkedQueue<MiningContext> contextPool = new ConcurrentLinkedQueue<>();

    private MiningContext acquireContext() {
        MiningContext c = contextPool.poll();
        return (c != null) ? c : new MiningContext();
    }

    private void releaseContext(MiningContext c) {
        c.patLen = 0;
        contextPool.offer(c);
    }

    // ============================ ENTRY POINT ============================

    public void runAlgorithm(String dataPath, String utilPath, String outputFolder,
                             String datasetName, double minRatio, double maxRegRatio) throws IOException {
        resetState();
        loadData(dataPath, utilPath, minRatio, maxRegRatio); // I/O excluded from runtime measurement
        mineCore();                                          // timed core (Phase 1 + Phase 2)

        File folder = new File(outputFolder);
        if (!folder.exists()) folder.mkdirs();
        String fileName = String.format("%s_%s_su%s_reg%s_patterns.txt",
                label, datasetName, trimNum(minRatio), trimNum(maxRegRatio));
        savePatternsToFile(outputFolder + File.separator + fileName);
    }

    /**
     * ADDED for the incremental harness of the companion study (project 07). Mines an ALREADY-PARSED
     * quantitative database, skipping file I/O: utilities in {@code db} are final (qty x external
     * profit) and items are sorted within each event, exactly what {@link #loadData} produces.
     * The timed mining core {@link #mineCore()} is SHARED with the file entry point and is UNCHANGED
     * from the original implementation, so the measured mining cost is identical to the paper's.
     */
    public void runAlgorithmInMemory(List<List<int[]>> db, double minRatio, double maxRegRatio) {
        resetState();
        long[] pl = Prof.start();
        loadFromMemory(db, minRatio, maxRegRatio);
        Prof.stop("engine.load", pl);
        mineCore();
    }

    /** Timed mining core: Phase 1 (parallel preprocessing) + Phase 2 (parallel recursive mining). */
    private void mineCore() {
        if (measureMemory) MemMeter.reset(); // GC prior garbage + re-base peak marker for a clean per-run measurement

        startTimestamp = System.currentTimeMillis();
        deadlineMillis = deadlineEpochMillis > 0 ? deadlineEpochMillis
                : (timeoutMs > 0) ? startTimestamp + timeoutMs : Long.MAX_VALUE;
        cpuStartNanos = THREAD_MX.isCurrentThreadCpuTimeSupported()
                ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
        checkMemory();

        long[] pp = Prof.start();
        pool = new ForkJoinPool(Math.max(1, numThreads));
        Prof.stop("engine.poolCreate", pp);
        try {
            // ----- Phase 1: parallel preprocessing -----
            // Tier 0 always runs to build the dense mapping (itemToDense/denseToItem) --
            // all context buffers are indexed by dense id. The SWU/regularity breadth filter
            // applies only when a "promising set" is active (EUCS or LA-PEU); the Baseline
            // configuration (no EUCS, PEU bound only) skips the filter so all items receive
            // a dense id -- preserving exact RHUSP semantics with only node-level pruning.
            long t0 = System.currentTimeMillis();
            tier0BuildValidItemsParallel();
            if (useEUCS) buildEUCSParallel();
            preprocessMs = System.currentTimeMillis() - t0;
            checkMemory();

            // ----- Phase 2: parallel recursive mining -----
            InstanceList init = new InstanceList(numSeq);
            for (int s = 0; s < numSeq; s++) init.add(s, -1, 0L);
            long[] pm = Prof.start();
            try {
                if (parallelStrategy == STRAT_STATIC) runStaticRoot(init);
                else pool.invoke(new RootTask(init)); // STEAL / RDLB: fork at root level
            } finally { Prof.stop("engine.mine", pm); }
        } finally {
            long[] ps = Prof.start();
            shutdownPool(pool); // clean up workers (even after timeout/OOM) to avoid contaminating the next run
            Prof.stop("engine.poolShutdown.wallOnly", ps);
            endTimestamp = System.currentTimeMillis();
            cpuEndNanos = THREAD_MX.isCurrentThreadCpuTimeSupported()
                    ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
            maxMemory = MemMeter.peakBytes(); // true peak for this run
        }

        // Merge atomic counters into public fields
        exploredNodes = exploredNodesA.sum();
        candidateCount = candidateCountA.sum();
        prunedEucs = prunedEucsA.sum();
        prunedBound = prunedBoundA.sum();
        prunedNodeCond = prunedNodeCondA.sum();
        prunedRegularity = prunedRegularityA.sum();
        eucsVisited = eucsVisitedA.sum();
        eucsDeadReverse = eucsDeadReverseA.sum();
    }

    /**
     * ADDED: build the same three per-sequence lists that {@link #loadData} produces, but from an
     * in-memory quantitative DB ({@code List<List<int[]>>}, each event = {item,util,item,util,...}).
     * Applies the identical per-event item ordering, then hands off to the shared CSR builder.
     */
    private void loadFromMemory(List<List<int[]>> db, double minRatio, double maxRegRatio) {
        List<int[]> seqItemsList = new ArrayList<>();
        List<long[]> seqUtilsList = new ArrayList<>();
        List<int[]> seqEvtLenList = new ArrayList<>();
        for (List<int[]> seq : db) {
            int nPos = 0;
            for (int[] ev : seq) nPos += ev.length / 2;
            if (nPos == 0) continue;
            int[] its = new int[nPos];
            long[] uts = new long[nPos];
            int[] evl = new int[seq.size()];
            int p = 0, e = 0;
            for (int[] ev : seq) {
                int n = ev.length / 2;
                if (n == 0) continue;
                int evStart = p;
                for (int k = 0; k < n; k++) {
                    int itemId = ev[2 * k];
                    if (itemId > maxItemId) maxItemId = itemId;
                    its[p] = itemId;
                    uts[p] = ev[2 * k + 1];
                    p++;
                }
                insertionSortPaired(its, uts, evStart, p - 1);   // same per-event ordering as loadData
                evl[e++] = n;
            }
            seqItemsList.add(Arrays.copyOf(its, p));
            seqUtilsList.add(Arrays.copyOf(uts, p));
            seqEvtLenList.add(Arrays.copyOf(evl, e));
        }
        buildCsr(seqItemsList, seqUtilsList, seqEvtLenList, minRatio, maxRegRatio);
    }

    private void resetState() {
        stopMemorySampler();
        finalPatterns.clear();
        maxMemory = 0;
        exploredNodesA.reset(); candidateCountA.reset(); eucsVisitedA.reset(); eucsDeadReverseA.reset();
        eucsBuildUsed = -1; eucsSketchCells = eucsSketchDropped = 0; eucsSketchRefused = false;
        prunedEucsA.reset(); prunedBoundA.reset(); prunedNodeCondA.reset(); prunedRegularityA.reset();
        exploredNodes = candidateCount = 0;
        prunedEucs = prunedBound = prunedNodeCond = prunedRegularity = 0;
        cpuStartNanos = cpuEndNanos = -1L;
        preprocessMs = 0; maxItemId = 0; maxEventsPerSeq = 0; totalDBUtility = 0;
        numValid = 0; eucsPairsI = 0; eucsPairsS = 0;
        adjIStart = adjSStart = null; adjIItem = adjSItem = null; adjIUtil = adjSUtil = null;
        itemToDense = null; denseToItem = null;
    }

    // ============================ PHASE 1: PARALLEL PREPROCESSING ============================

    /**
     * Parallel scan: each thread processes a slice of sequences, accumulates TWU and
     * per-item occurrence periods locally, then merges into global arrays via reduction.
     * The period merge must stitch cross-slice gaps: the largest gap spanning the boundary
     * between two adjacent slices is computed from the last SID in the earlier slice and
     * the first SID in the later slice. (Phase 1)
     */
    private void tier0BuildValidItemsParallel() {
        final int n = maxItemId + 1;
        final int T = Math.max(1, numThreads);
        final int chunk = (numSeq + T - 1) / T;
        long[] p0 = Prof.start();

        // Per-thread local results
        final long[][] twuL = new long[T][];
        final int[][] firstL = new int[T][];   // first SID where item appears in this slice
        final int[][] lastL = new int[T][];    // last SID where item appears in this slice
        final int[][] maxPerL = new int[T][];  // largest gap seen WITHIN this slice

        ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[T];
        for (int t = 0; t < T; t++) {
            final int tid = t;
            final int lo = Math.min(numSeq, tid * chunk);
            final int hi = Math.min(numSeq, lo + chunk);
            tasks[t] = pool.submit(() -> {
                long[] twu = new long[n];
                int[] first = new int[n]; Arrays.fill(first, -1);
                int[] last = new int[n]; Arrays.fill(last, -1);
                int[] maxPer = new int[n];
                int[] seen = new int[n]; Arrays.fill(seen, -1);
                for (int s = lo; s < hi; s++) {
                    long su = seqUtility[s];
                    for (int p = seqPosStart[s]; p < seqPosStart[s + 1]; p++) {
                        int it = items[p];
                        if (seen[it] != s) {
                            seen[it] = s;
                            twu[it] += su;
                            if (first[it] == -1) first[it] = s;
                            else { int per = s - last[it]; if (per > maxPer[it]) maxPer[it] = per; }
                            last[it] = s;
                        }
                    }
                }
                twuL[tid] = twu; firstL[tid] = first; lastL[tid] = last; maxPerL[tid] = maxPer;
            });
        }
        for (ForkJoinTask<?> tk : tasks) tk.join();
        Prof.stop("engine.tier0.scan", p0);
        long[] p1 = Prof.start();
        checkMemory();

        // Merge: TWU sums; periods stitched in SID order across slices.
        long[] twu = new long[n];
        int[] globalFirst = new int[n]; Arrays.fill(globalFirst, -1);
        int[] globalLast = new int[n]; Arrays.fill(globalLast, -1);
        int[] maxPer = new int[n];
        for (int t = 0; t < T; t++) {
            if (twuL[t] == null) continue;
            for (int it = 0; it < n; it++) {
                twu[it] += twuL[t][it];
                if (firstL[t][it] == -1) continue;
                // intra-slice period
                if (maxPerL[t][it] > maxPer[it]) maxPer[it] = maxPerL[t][it];
                // cross-slice period: gap from last occurrence in previous slice to first in this one
                if (globalLast[it] != -1) {
                    int bridge = firstL[t][it] - globalLast[it];
                    if (bridge > maxPer[it]) maxPer[it] = bridge;
                }
                if (globalFirst[it] == -1) globalFirst[it] = firstL[t][it];
                globalLast[it] = lastL[t][it];
            }
        }

        // Apply the SWU/regularity breadth filter only when a "promising set" is active
        // (EUCS or LA-PEU). The Baseline (no EUCS, PEU bound only) skips this filter --
        // all items get a dense id, preserving exact RHUSP semantics; "dense" here simply
        // compacts the sparse id space.
        boolean applyFilter = useEUCS || boundMode == BOUND_LA_PEU;
        itemToDense = new int[n]; Arrays.fill(itemToDense, -1);
        int[] tmp = new int[n];
        int cnt = 0;
        for (int it = 0; it < n; it++) {
            if (globalFirst[it] == -1) continue;          // item never occurs
            if (applyFilter) {
                if (twu[it] < minUtility) continue;       // SWU breadth prune
                if (useRegPruning) {
                    // boundary periods: from virtual sequence 0 to first occurrence, and from
                    // last occurrence to virtual sequence N (one past the end)
                    int mp = maxPer[it];
                    if (!windowMode || windowIsSuffix) {
                        // Leading gap is a TRUE gap only when the data starts at global sequence 0;
                        // for a suffix window it is a lower bound of the true gap (windowIsSuffix).
                        int head = globalFirst[it] + 1;    // = firstSid + 1, with virtual boundary at 0
                        if (head > mp) mp = head;
                    }
                    if ((!seedMode && !windowMode) || (windowIsSuffix && floorPrune)) {
                        // The TRAILING gap shrinks as soon as a later batch contains the item, so it must
                        // not filter items when seeding an incremental miner (see the seedMode javadoc).
                        int tail = numSeq - globalLast[it];    // = N - lastSid
                        if (tail > mp) mp = tail;
                    }
                    if (mp > maxRegularity) continue;
                }
            }
            itemToDense[it] = cnt; tmp[cnt++] = it;
        }
        denseToItem = Arrays.copyOf(tmp, cnt);
        numValid = cnt;
        Prof.stop("engine.tier0.merge", p1);
    }

    /**
     * Build the co-occurrence structure with the selected construction (see {@link #eucsBuild}). Every
     * construction yields the same kept pair set; they differ only in memory and in how much of the work
     * runs in parallel.
     */
    private void buildEUCSParallel() {
        int mode = chooseEucsBuild();
        eucsBuildUsed = mode;
        if (!compactEucs || mode == EUCS_HASH) { buildEucsHash(); return; }
        if (mode == EUCS_DENSE) buildEucsDense();
        else buildEucsPartitioned(mode == EUCS_SKETCH);
    }

    /**
     * Automatic choice: a dense matrix per thread when all thread copies fit {@link #EUCS_DENSE_BUDGET},
     * otherwise key-partitioned hash tables. The two-pass sketch filter is used only when asked for.
     */
    private int chooseEucsBuild() {
        if (eucsBuild != EUCS_AUTO) return eucsBuild;
        return denseBytesTotal() <= EUCS_DENSE_BUDGET ? EUCS_DENSE : EUCS_PARTITIONED;
    }

    /** Memory of the per-thread dense matrices: full square for s-pairs, strict upper triangle for i-pairs. */
    private long denseBytesTotal() {
        long n = numValid;
        return (n * n + n * (n - 1) / 2) * 8L * Math.max(1, numThreads);
    }

    /**
     * Original construction: one hash map per thread over its slice, merged into one map on the calling
     * thread. Kept for comparison; the merge is serial and every thread map holds most distinct pairs.
     */
    private void buildEucsHash() {
        final int T = Math.max(1, numThreads);
        final int chunk = (numSeq + T - 1) / T;
        final PairAccMap[] mapsI = new PairAccMap[T];
        final PairAccMap[] mapsS = new PairAccMap[T];
        long[] e0 = Prof.start();

        ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[T];
        for (int t = 0; t < T; t++) {
            final int tid = t;
            final int lo = Math.min(numSeq, tid * chunk);
            final int hi = Math.min(numSeq, lo + chunk);
            tasks[t] = pool.submit(() -> {
                PairAccMap mapI = new PairAccMap(1 << 14);
                PairAccMap mapS = new PairAccMap(1 << 14);
                int[] seenBefore = new int[Math.max(numValid, 1)];
                int[] seenStamp = new int[Math.max(numValid, 1)]; Arrays.fill(seenStamp, -1);
                int[] evtBuf = new int[maxItemId + 1];
                for (int s = lo; s < hi; s++) {
                    long su = seqUtility[s];
                    int nb = 0;
                    for (int e = seqEvtStart[s]; e < seqEvtStart[s + 1]; e++) {
                        int m = 0;
                        for (int p = evtPosStart[e]; p < evtPosStart[e + 1]; p++) {
                            int d = itemToDense[items[p]];
                            if (d >= 0) evtBuf[m++] = d;
                        }
                        for (int x = 0; x < m; x++)
                            for (int y = x + 1; y < m; y++) {
                                int a = evtBuf[x], b = evtBuf[y];
                                if (a == b) continue;
                                mapI.add(a < b ? pack(a, b) : pack(b, a), s, su);
                            }
                        for (int y = 0; y < m; y++) {
                            int v = evtBuf[y];
                            for (int k = 0; k < nb; k++) mapS.add(pack(seenBefore[k], v), s, su);
                        }
                        for (int y = 0; y < m; y++) {
                            int v = evtBuf[y];
                            if (seenStamp[v] != s) { seenStamp[v] = s; seenBefore[nb++] = v; }
                        }
                    }
                }
                mapsI[tid] = mapI; mapsS[tid] = mapS;
            });
        }
        for (ForkJoinTask<?> tk : tasks) tk.join();
        Prof.stop("engine.eucs.scan", e0);
        long[] e1 = Prof.start();
        checkMemory();

        // Merge per-thread maps into immutable adjacency lists. Sequential, but cheap relative
        // to the scan. Per-pair accumulation is correct: each map already deduplicates by SID
        // within its slice, and slices are disjoint by SID.
        int[] cntI = new int[numValid + 1];
        int[] cntS = new int[numValid + 1];
        PairAccMap mergedI = new PairAccMap(1 << 16);
        PairAccMap mergedS = new PairAccMap(1 << 16);
        long slots = 0;
        for (int t = 0; t < T; t++) {
            if (mapsI[t] != null) { slots += mapsI[t].keys.length; mergeInto(mergedI, mapsI[t]); }
            if (mapsS[t] != null) { slots += mapsS[t].keys.length; mergeInto(mergedS, mapsS[t]); }
            mapsI[t] = null; mapsS[t] = null;
        }
        eucsThreadMapSlots = slots;
        eucsMergedSlots = mergedI.keys.length + mergedS.keys.length;
        eucsDistinctI = mergedI.size; eucsDistinctS = mergedS.size;
        long kI = 0, kS = 0;
        for (int j = 0; j < mergedI.keys.length; j++) if (mergedI.keys[j] != -1L && mergedI.acc[j] >= minUtility) kI++;
        for (int j = 0; j < mergedS.keys.length; j++) if (mergedS.keys[j] != -1L && mergedS.acc[j] >= minUtility) kS++;
        eucsKeptI = kI; eucsKeptS = kS;
        if (compactEucs) { Prof.stop("engine.eucs.merge", e1); buildCompactAdjacency(mergedI, mergedS); return; }
        for (int j = 0; j < mergedI.keys.length; j++)
            if (mergedI.keys[j] != -1L) { cntI[(int) (mergedI.keys[j] >>> 32)]++; cntI[(int) mergedI.keys[j]]++; }
        for (int j = 0; j < mergedS.keys.length; j++)
            if (mergedS.keys[j] != -1L) cntS[(int) (mergedS.keys[j] >>> 32)]++;

        adjIStart = prefixSum(cntI); adjSStart = prefixSum(cntS);
        adjIItem = new int[adjIStart[numValid]]; adjIUtil = new long[adjIStart[numValid]];
        adjSItem = new int[adjSStart[numValid]]; adjSUtil = new long[adjSStart[numValid]];
        int[] curI = Arrays.copyOf(adjIStart, numValid);
        int[] curS = Arrays.copyOf(adjSStart, numValid);
        for (int j = 0; j < mergedI.keys.length; j++) {
            if (mergedI.keys[j] == -1L) continue;
            int a = (int) (mergedI.keys[j] >>> 32), b = (int) mergedI.keys[j];
            long u = mergedI.acc[j];
            adjIItem[curI[a]] = denseToItem[b]; adjIUtil[curI[a]++] = u;
            adjIItem[curI[b]] = denseToItem[a]; adjIUtil[curI[b]++] = u;
        }
        for (int j = 0; j < mergedS.keys.length; j++) {
            if (mergedS.keys[j] == -1L) continue;
            int a = (int) (mergedS.keys[j] >>> 32), b = (int) mergedS.keys[j];
            adjSItem[curS[a]] = denseToItem[b]; adjSUtil[curS[a]++] = mergedS.acc[j];
        }
        eucsPairsI = adjIStart[numValid];
        eucsPairsS = adjSStart[numValid];
        eucsAdjBytes = (eucsPairsI + eucsPairsS) * 12L + (long) (numValid + 1) * 2 * 4L;

        // Sort adjacency slices in parallel by descending utility to enable early-break pruning.
        // Partitioned into ~4*T blocks (not one task per item) to avoid flooding the pool
        // with thousands of micro-tasks when the item cardinality is large.
        Prof.stop("engine.eucs.merge", e1);
        long[] e2 = Prof.start();
        final int[] adjIItemF = adjIItem; final long[] adjIUtilF = adjIUtil;
        final int[] adjSItemF = adjSItem; final long[] adjSUtilF = adjSUtil;
        final int[] adjIStartF = adjIStart; final int[] adjSStartF = adjSStart;
        final int T2 = Math.max(1, numThreads);
        final int blocks = Math.max(1, T2 * 4);
        final int blockSize = (numValid + blocks - 1) / blocks;
        List<RecursiveAction> subs = new ArrayList<>();
        for (int b = 0; b < blocks; b++) {
            final int lo = b * blockSize;
            final int hi = Math.min(numValid, lo + blockSize);
            if (lo >= hi) break;
            subs.add(new RecursiveAction() {
                @Override protected void compute() {
                    for (int d = lo; d < hi; d++) {
                        sortDescByUtil(adjIItemF, adjIUtilF, adjIStartF[d], adjIStartF[d + 1] - 1);
                        sortDescByUtil(adjSItemF, adjSUtilF, adjSStartF[d], adjSStartF[d + 1] - 1);
                    }
                }
            });
        }
        if (!subs.isEmpty()) pool.invoke(new RecursiveAction() {
            @Override protected void compute() { invokeAll(subs); }
        });
        Prof.stop("engine.eucs.sort", e2);
        checkMemory();
    }

    /**
     * Compact lists: i-pairs forward only (smaller dense id -> larger), only pairs reaching minUtility, items
     * only. Entries are ordered by item id so exploration order stays deterministic.
     */
    private void buildCompactAdjacency(PairAccMap mergedI, PairAccMap mergedS) {
        long[] e2 = Prof.start();
        int[] cntI = new int[numValid + 1], cntS = new int[numValid + 1];
        for (int j = 0; j < mergedI.keys.length; j++) {
            if (mergedI.keys[j] == -1L) continue;
            int a = EUCS_FAULT == 2 ? (int) mergedI.keys[j] : (int) (mergedI.keys[j] >>> 32);
            if (mergedI.acc[j] >= minUtility && EUCS_FAULT != 1) cntI[a]++;
        }
        for (int j = 0; j < mergedS.keys.length; j++) {
            if (mergedS.keys[j] == -1L) continue;
            int a = (int) (mergedS.keys[j] >>> 32);
            if (mergedS.acc[j] >= minUtility) cntS[a]++;
        }
        adjIStart = prefixSum(cntI); adjSStart = prefixSum(cntS);
        adjIItem = new int[adjIStart[numValid]]; adjSItem = new int[adjSStart[numValid]];
        adjIUtil = null; adjSUtil = null;
        int[] curI = Arrays.copyOf(adjIStart, numValid), curS = Arrays.copyOf(adjSStart, numValid);
        for (int j = 0; j < mergedI.keys.length; j++) {
            if (mergedI.keys[j] == -1L || mergedI.acc[j] < minUtility) continue;
            int a = (int) (mergedI.keys[j] >>> 32), b = (int) mergedI.keys[j];
            if (EUCS_FAULT == 1) continue;
            if (EUCS_FAULT == 2) { int t = a; a = b; b = t; }
            adjIItem[curI[a]++] = denseToItem[b];
        }
        for (int j = 0; j < mergedS.keys.length; j++) {
            if (mergedS.keys[j] == -1L || mergedS.acc[j] < minUtility) continue;
            int a = (int) (mergedS.keys[j] >>> 32), b = (int) mergedS.keys[j];
            adjSItem[curS[a]++] = denseToItem[b];
        }
        Prof.stop("engine.eucs.merge", e2 == null ? null : e2);
        long[] e3 = Prof.start();
        final int[] aI = adjIItem, aS = adjSItem, sI = adjIStart, sS = adjSStart;
        final int blocks = Math.max(1, Math.max(1, numThreads) * 4);
        final int blockSize = (numValid + blocks - 1) / blocks;
        List<RecursiveAction> subs = new ArrayList<>();
        for (int b = 0; b < blocks; b++) {
            final int lo = b * blockSize, hi = Math.min(numValid, lo + blockSize);
            if (lo >= hi) break;
            subs.add(new RecursiveAction() {
                @Override protected void compute() {
                    for (int d = lo; d < hi; d++) { Arrays.sort(aI, sI[d], sI[d + 1]); Arrays.sort(aS, sS[d], sS[d + 1]); }
                }
            });
        }
        if (!subs.isEmpty()) pool.invoke(new RecursiveAction() {
            @Override protected void compute() { invokeAll(subs); }
        });
        Prof.stop("engine.eucs.sort", e3);
        eucsPairsI = adjIStart[numValid];
        eucsPairsS = adjSStart[numValid];
        eucsAdjBytes = (eucsPairsI + eucsPairsS) * 4L + (long) (numValid + 1) * 2 * 4L;
        checkMemory();
    }

    // ============================ CO-OCCURRENCE: SHARED PAIR GENERATOR ============================

    /** Flag marking an s-pair key; i-pair keys have it clear. Dense ids are below 2^31, so keys never equal -1. */
    private static final long S_FLAG = 1L << 63;
    private static final long PAIR_MASK = ~S_FLAG;

    /** Per-thread scratch for {@link #collectPairs}. */
    private final class PairWs {
        final int[] evtBuf = new int[maxItemId + 1];
        final int[] seenBefore = new int[Math.max(numValid, 1)];
        final int[] seenStamp = new int[Math.max(numValid, 1)];
        final PairSet set = new PairSet(1 << 10);
        PairWs() { Arrays.fill(seenStamp, -1); }
    }

    /**
     * Distinct pairs of sequence {@code s}, each once: i-pairs (a,b), a &lt; b, sharing an event, and s-pairs
     * (a,b) with a in an event before one holding b. Every construction except the original consumes this,
     * so all of them add su(s) to a pair exactly once per sequence by construction. Returns the count; the
     * keys are {@code w.set.list[0..count)}.
     */
    private int collectPairs(int s, PairWs w) {
        PairSet set = w.set;
        set.reset();
        int nb = 0;
        int[] evtBuf = w.evtBuf, seenBefore = w.seenBefore, seenStamp = w.seenStamp;
        for (int e = seqEvtStart[s]; e < seqEvtStart[s + 1]; e++) {
            int m = 0;
            for (int p = evtPosStart[e]; p < evtPosStart[e + 1]; p++) {
                int d = itemToDense[items[p]];
                if (d >= 0) evtBuf[m++] = d;
            }
            for (int x = 0; x < m; x++)
                for (int y = x + 1; y < m; y++) {
                    int a = evtBuf[x], b = evtBuf[y];
                    if (a == b) continue;
                    set.add(a < b ? pack(a, b) : pack(b, a));
                }
            for (int y = 0; y < m; y++) {
                int v = evtBuf[y];
                for (int k = 0; k < nb; k++) set.add(pack(seenBefore[k], v) | S_FLAG);
            }
            for (int y = 0; y < m; y++) {
                int v = evtBuf[y];
                if (seenStamp[v] != s) { seenStamp[v] = s; seenBefore[nb++] = v; }
            }
        }
        return set.n;
    }

    /** Insertion-ordered set of pair keys, cleared in O(1) by bumping a stamp. */
    private static final class PairSet {
        long[] keys, list; int[] stamp; int n, cur;
        PairSet(int cap) { keys = new long[cap]; list = new long[cap]; stamp = new int[cap]; }
        void reset() {
            n = 0;
            if (++cur == Integer.MAX_VALUE) { Arrays.fill(stamp, 0); cur = 1; }
        }
        void add(long key) {
            int mask = keys.length - 1;
            int i = PairAccMap.hash(key) & mask;
            while (true) {
                if (stamp[i] != cur) {
                    stamp[i] = cur; keys[i] = key; list[n++] = key;
                    if (n * 5L > keys.length * 3L) grow();
                    return;
                }
                if (keys[i] == key) return;
                i = (i + 1) & mask;
            }
        }
        private void grow() {
            int c = keys.length << 1, mask = c - 1;
            keys = new long[c]; stamp = new int[c]; list = Arrays.copyOf(list, c);
            for (int j = 0; j < n; j++) {
                int i = PairAccMap.hash(list[j]) & mask;
                while (stamp[i] == cur) i = (i + 1) & mask;
                stamp[i] = cur; keys[i] = list[j];
            }
        }
    }

    /** Run {@code body(t, lo, hi)} over T equal sequence slices on the pool and wait. */
    private interface SliceBody { void run(int t, int lo, int hi); }
    private void forSlices(int T, SliceBody body) {
        final int chunk = (numSeq + T - 1) / T;
        ForkJoinTask<?>[] tasks = new ForkJoinTask<?>[T];
        for (int t = 0; t < T; t++) {
            final int tid = t, lo = Math.min(numSeq, tid * chunk), hi = Math.min(numSeq, lo + chunk);
            tasks[t] = pool.submit(() -> body.run(tid, lo, hi));
        }
        for (ForkJoinTask<?> tk : tasks) tk.join();
    }

    /** Run {@code body(i)} for i in [0,n) on the pool in about 4T blocks and wait. */
    private void forBlocks(int n, IntConsumerLike body) {
        final int blocks = Math.max(1, Math.max(1, numThreads) * 4), size = (n + blocks - 1) / blocks;
        List<RecursiveAction> subs = new ArrayList<>();
        for (int b = 0; b < blocks; b++) {
            final int lo = b * size, hi = Math.min(n, lo + size);
            if (lo >= hi) break;
            subs.add(new RecursiveAction() {
                @Override protected void compute() { for (int i = lo; i < hi; i++) body.accept(i); }
            });
        }
        if (!subs.isEmpty()) pool.invoke(new RecursiveAction() {
            @Override protected void compute() { invokeAll(subs); }
        });
    }
    private interface IntConsumerLike { void accept(int i); }

    // ============================ CO-OCCURRENCE: DENSE MATRICES ============================

    /** Index of i-pair (a,b), a &lt; b, in the strict upper triangle of an n x n matrix. */
    private static int triIndex(int a, int b, int n) {
        return (int) ((long) a * (2L * n - a - 1) / 2 + (b - a - 1));
    }

    /**
     * Dense construction: each thread adds into its own matrices (s-pairs n x n, i-pairs upper triangle),
     * the copies are summed in parallel by index blocks, and each row becomes an adjacency slice in
     * ascending dense id, which is ascending item id, so no sort is needed.
     */
    private void buildEucsDense() {
        final int n = numValid, T = Math.max(1, numThreads);
        final int sq = n * n, tri = (int) ((long) n * (n - 1) / 2);
        final long[][] accS = new long[T][], accI = new long[T][];
        long[] e0 = Prof.start();
        forSlices(T, (t, lo, hi) -> {
            long[] aS = new long[sq], aI = new long[tri];
            PairWs w = new PairWs();
            for (int s = lo; s < hi; s++) {
                long su = seqUtility[s];
                int c = collectPairs(s, w);
                long[] keys = w.set.list;
                for (int j = 0; j < c; j++) {
                    long k = keys[j];
                    int a = (int) ((k & PAIR_MASK) >>> 32), b = (int) k;
                    if (k < 0) {
                        if (EUCS_FAULT == 3) aS[b * n + a] += su;     // deliberate defect: s-pair stored backward
                        else aS[a * n + b] += su;
                    } else aI[triIndex(a, b, n)] += su;
                }
            }
            accS[t] = aS; accI[t] = aI;
        });
        Prof.stop("engine.eucs.scan", e0);
        checkMemory();

        long[] e1 = Prof.start();
        final long[] S = accS[0], I = accI[0];
        if (T > 1) {
            forBlocks(sq, i -> { long v = 0; for (int t = 1; t < T; t++) v += accS[t][i]; S[i] += v; });
            forBlocks(tri, i -> { long v = 0; for (int t = 1; t < T; t++) v += accI[t][i]; I[i] += v; });
        }
        for (int t = 1; t < T; t++) { accS[t] = null; accI[t] = null; }
        Prof.stop("engine.eucs.merge", e1);

        long[] e2 = Prof.start();
        final int[] cntI = new int[n + 1], cntS = new int[n + 1], dI = new int[n], dS = new int[n];
        final long mu = minUtility;
        forBlocks(n, a -> {
            int ci = 0, cs = 0, di = 0, ds = 0;
            for (int b = 0; b < n; b++) { long v = S[a * n + b]; if (v > 0) ds++; if (v >= mu) cs++; }
            for (int b = a + 1; b < n; b++) { long v = I[triIndex(a, b, n)]; if (v > 0) di++; if (v >= mu) ci++; }
            cntI[a] = ci; cntS[a] = cs; dI[a] = di; dS[a] = ds;
        });
        adjIStart = prefixSum(cntI); adjSStart = prefixSum(cntS);
        final int[] aIt = new int[adjIStart[n]], aSt = new int[adjSStart[n]];
        final int[] sI = adjIStart, sS = adjSStart;
        forBlocks(n, a -> {
            int pi = sI[a], ps = sS[a];
            for (int b = 0; b < n; b++) if (S[a * n + b] >= mu) aSt[ps++] = denseToItem[b];
            for (int b = a + 1; b < n; b++) if (I[triIndex(a, b, n)] >= mu) aIt[pi++] = denseToItem[b];
        });
        adjIItem = aIt; adjSItem = aSt; adjIUtil = null; adjSUtil = null;
        Prof.stop("engine.eucs.adjacency", e2);
        long di = 0, ds = 0;
        for (int a = 0; a < n; a++) { di += dI[a]; ds += dS[a]; }
        eucsDistinctI = di; eucsDistinctS = ds;
        eucsKeptI = adjIStart[n]; eucsKeptS = adjSStart[n];
        eucsThreadMapSlots = (long) T * (sq + tri); eucsMergedSlots = 0;
        finishCompactStats();
        checkMemory();
    }

    // ============================ CO-OCCURRENCE: PARTITIONED HASH TABLES (+ SKETCH FILTER) ============================

    private static final java.lang.invoke.VarHandle LONGS = java.lang.invoke.MethodHandles.arrayElementVarHandle(long[].class);
    private static final int SKETCH_ROWS = 2;
    private static final long[] SKETCH_SEED = {0x243F6A8885A308D3L, 0x13198A2E03707344L};
    private static final long[] SKETCH_MUL = {0x9E3779B97F4A7C15L, 0xC2B2AE3D27D4EB4FL};

    private static int sketchSlot(long key, int r, int logW) {
        return (int) (((key ^ SKETCH_SEED[r]) * SKETCH_MUL[r]) >>> (64 - logW));
    }

    /** Sketch width (log2): from {@code -Deucs.sketchLog}, else about 2 n^2 counters per row, clamped to [16, 23]. */
    private int sketchLogWidth() {
        Integer forced = Integer.getInteger("eucs.sketchLog");
        if (forced != null) return Math.max(1, Math.min(28, forced));
        long n2 = Math.max(1L, (long) numValid * numValid);
        int bits = 64 - Long.numberOfLeadingZeros(n2 - 1) + 1;
        return Math.max(16, Math.min(23, bits));
    }

    /**
     * Key-partitioned construction. Each thread keeps P small hash tables, one per key range; partition p
     * of every thread is merged by one task, so the merge runs in parallel and no table ever holds the
     * pairs of another partition. With {@code sketch}, a first pass adds su(s) of every distinct pair to a
     * shared count-min table, and the second pass counts exactly only the pairs whose estimate (minimum
     * over rows) reaches minUtility. The estimate never falls below the true value because every counter
     * is a sum of non-negative pair values that includes the pair itself, so no pair reaching minUtility
     * is dropped and the kept set is the same as without the filter.
     */
    private void buildEucsPartitioned(boolean sketch) {
        final int T = Math.max(1, numThreads);
        final int P = Integer.highestOneBit(Math.max(1, 2 * T - 1)) * 2;
        final int logP = Integer.numberOfTrailingZeros(P);
        final long mu = minUtility;

        if (sketch) {
            // The filter is sound only for non-negative sequence utilities; otherwise count every pair exactly.
            for (int q = 0; q < numSeq; q++) if (seqUtility[q] < 0) { sketch = false; eucsSketchRefused = true; break; }
        }
        final long[] sk;
        final int logW;
        if (sketch) {
            long[] e = Prof.start();
            logW = sketchLogWidth();
            final int W = 1 << logW;
            sk = new long[SKETCH_ROWS * W];
            forSlices(T, (t, lo, hi) -> {
                PairWs w = new PairWs();
                for (int s = lo; s < hi; s++) {
                    long su = seqUtility[s];
                    if (su == 0) continue;
                    int c = collectPairs(s, w);
                    long[] keys = w.set.list;
                    for (int j = 0; j < c; j++)
                        for (int r = 0; r < SKETCH_ROWS; r++)
                            LONGS.getAndAdd(sk, r * W + sketchSlot(keys[j], r, logW), su);
                }
            });
            eucsSketchCells = (long) SKETCH_ROWS * W;
            Prof.stop("engine.eucs.sketch", e);
            checkMemory();
        } else { sk = null; logW = 0; }

        final PairAccMap[][] parts = new PairAccMap[T][P];
        final long[] dropped = new long[T];
        long[] e0 = Prof.start();
        forSlices(T, (t, lo, hi) -> {
            PairAccMap[] mine = parts[t];
            for (int q = 0; q < P; q++) mine[q] = new PairAccMap(16);
            PairWs w = new PairWs();
            long drop = 0;
            final int W = 1 << logW;
            for (int s = lo; s < hi; s++) {
                long su = seqUtility[s];
                int c = collectPairs(s, w);
                long[] keys = w.set.list;
                for (int j = 0; j < c; j++) {
                    long k = keys[j];
                    if (sk != null) {
                        long est = Long.MAX_VALUE;
                        for (int r = 0; r < SKETCH_ROWS; r++) est = Math.min(est, sk[r * W + sketchSlot(k, r, logW)]);
                        if (EUCS_FAULT == 5) est -= su;              // deliberate defect: an estimate that can fall short
                        if (est < mu) { drop++; continue; }
                    }
                    mine[partitionOf(k, logP)].addAcc(k, su);
                }
            }
            dropped[t] = drop;
        });
        long slots = 0, dsum = 0;
        for (int t = 0; t < T; t++) { dsum += dropped[t]; for (int q = 0; q < P; q++) slots += parts[t][q].keys.length; }
        eucsThreadMapSlots = slots; eucsSketchDropped = dsum;
        Prof.stop("engine.eucs.scan", e0);
        checkMemory();

        long[] e1 = Prof.start();
        final long[][] keptP = new long[P][];
        final long[] distinctI = new long[P], distinctS = new long[P], mergedSlots = new long[P];
        forBlocksExact(P, q -> {
            if (EUCS_FAULT == 4 && q == 0) { keptP[q] = new long[0]; return; }   // deliberate defect: a partition lost
            // The same pair usually sits in several thread tables, so the sum of their sizes overstates the distinct
            // count by up to T times; start from the largest one (a lower bound) and let the table grow.
            int largest = 0;
            for (int t = 0; t < T; t++) largest = Math.max(largest, parts[t][q].size);
            PairAccMap m = new PairAccMap(largest + (largest >> 1));
            for (int t = 0; t < T; t++) { mergeInto(m, parts[t][q]); parts[t][q] = null; }
            long[] kept = new long[16];
            int nk = 0; long di = 0, ds = 0;
            for (int j = 0; j < m.keys.length; j++) {
                long k = m.keys[j];
                if (k == -1L) continue;
                if (k < 0) ds++; else di++;
                if (m.acc[j] >= mu) { if (nk == kept.length) kept = Arrays.copyOf(kept, nk * 2); kept[nk++] = k; }
            }
            keptP[q] = Arrays.copyOf(kept, nk);
            distinctI[q] = di; distinctS[q] = ds; mergedSlots[q] = m.keys.length;
        });
        Prof.stop("engine.eucs.merge", e1);

        long[] e2 = Prof.start();
        final int n = numValid;
        int[] cntI = new int[n + 1], cntS = new int[n + 1];
        long di = 0, ds = 0, ms = 0;
        for (int q = 0; q < P; q++) {
            di += distinctI[q]; ds += distinctS[q]; ms += mergedSlots[q];
            for (long k : keptP[q]) {
                int a = (int) ((k & PAIR_MASK) >>> 32);
                if (k < 0) cntS[a]++; else cntI[a]++;
            }
        }
        adjIStart = prefixSum(cntI); adjSStart = prefixSum(cntS);
        adjIItem = new int[adjIStart[n]]; adjSItem = new int[adjSStart[n]]; adjIUtil = null; adjSUtil = null;
        int[] curI = Arrays.copyOf(adjIStart, n), curS = Arrays.copyOf(adjSStart, n);
        for (int q = 0; q < P; q++)
            for (long k : keptP[q]) {
                int a = (int) ((k & PAIR_MASK) >>> 32), b = (int) k;
                if (k < 0) adjSItem[curS[a]++] = denseToItem[b]; else adjIItem[curI[a]++] = denseToItem[b];
            }
        final int[] aI = adjIItem, aS = adjSItem, sI = adjIStart, sS = adjSStart;
        forBlocks(n, a -> { Arrays.sort(aI, sI[a], sI[a + 1]); Arrays.sort(aS, sS[a], sS[a + 1]); });
        Prof.stop("engine.eucs.adjacency", e2);
        eucsDistinctI = di; eucsDistinctS = ds; eucsMergedSlots = ms;
        eucsKeptI = adjIStart[n]; eucsKeptS = adjSStart[n];
        finishCompactStats();
        checkMemory();
    }

    /** Partition of a key: top bits of the multiplicative hash (independent of the in-table slot bits). */
    private static int partitionOf(long key, int logP) {
        return logP == 0 ? 0 : (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - logP));
    }

    /** One task per index (used for the P partitions, which are few and heavy). */
    private void forBlocksExact(int n, IntConsumerLike body) {
        List<RecursiveAction> subs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int q = i;
            subs.add(new RecursiveAction() { @Override protected void compute() { body.accept(q); } });
        }
        if (!subs.isEmpty()) pool.invoke(new RecursiveAction() {
            @Override protected void compute() { invokeAll(subs); }
        });
    }

    private void finishCompactStats() {
        eucsPairsI = adjIStart[numValid];
        eucsPairsS = adjSStart[numValid];
        eucsAdjBytes = (eucsPairsI + eucsPairsS) * 4L + (long) (numValid + 1) * 2 * 4L;
    }

    /**
     * The kept pair set as sorted keys (s-pairs carry the top bit), read back from the adjacency lists, for
     * comparing constructions pair by pair. Compact mode only.
     */
    public long[] keptPairKeys() {
        long[] out = new long[adjIStart[numValid] + adjSStart[numValid]];
        int k = 0;
        for (int a = 0; a < numValid; a++) {
            for (int j = adjIStart[a]; j < adjIStart[a + 1]; j++) out[k++] = pack(a, itemToDense[adjIItem[j]]);
            for (int j = adjSStart[a]; j < adjSStart[a + 1]; j++) out[k++] = pack(a, itemToDense[adjSItem[j]]) | S_FLAG;
        }
        Arrays.sort(out);
        return out;
    }

    private static void mergeInto(PairAccMap dst, PairAccMap src) {
        for (int j = 0; j < src.keys.length; j++)
            if (src.keys[j] != -1L) dst.addAcc(src.keys[j], src.acc[j]);
    }

    // ----- Open-addressing pair hash map (same as sequential; adds addAcc for merging) -----
    private static final class PairAccMap {
        long[] keys; long[] acc; int[] last; int size;
        PairAccMap(int cap) {
            int c = Integer.highestOneBit(Math.max(cap, 16) * 2 - 1);
            keys = new long[c]; Arrays.fill(keys, -1L);
            acc = new long[c]; last = new int[c];
        }
        void add(long key, int sid, long su) {
            int mask = keys.length - 1;
            int i = hash(key) & mask;
            while (true) {
                long k = keys[i];
                if (k == key) { if (last[i] != sid) { last[i] = sid; acc[i] += su; } return; }
                if (k == -1L) {
                    keys[i] = key; last[i] = sid; acc[i] = su; size++;
                    if (size * 5L > keys.length * 3L) grow();
                    return;
                }
                i = (i + 1) & mask;
            }
        }
        /** Add a pre-aggregated utility value (used when merging per-thread maps). */
        void addAcc(long key, long val) {
            int mask = keys.length - 1;
            int i = hash(key) & mask;
            while (true) {
                long k = keys[i];
                if (k == key) { acc[i] += val; return; }
                if (k == -1L) {
                    keys[i] = key; acc[i] = val; size++;
                    if (size * 5L > keys.length * 3L) grow();
                    return;
                }
                i = (i + 1) & mask;
            }
        }
        static int hash(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
        private void grow() {
            long[] ok = keys; long[] oa = acc; int[] ol = last;
            int c = keys.length << 1; int mask = c - 1;
            keys = new long[c]; Arrays.fill(keys, -1L);
            acc = new long[c]; last = new int[c];
            for (int j = 0; j < ok.length; j++) {
                if (ok[j] == -1L) continue;
                int i = hash(ok[j]) & mask;
                while (keys[i] != -1L) i = (i + 1) & mask;
                keys[i] = ok[j]; acc[i] = oa[j]; last[i] = ol[j];
            }
        }
    }

    private static long pack(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    private static int[] prefixSum(int[] cnt) {
        int[] out = new int[cnt.length];
        int acc = 0;
        for (int i = 0; i < cnt.length; i++) { out[i] = acc; acc += cnt[i]; }
        return out;
    }

    private static void sortDescByUtil(int[] it, long[] ut, int lo, int hi) {
        while (lo < hi) {
            long pivot = ut[lo + (hi - lo) / 2];
            int i = lo, j = hi;
            while (i <= j) {
                while (ut[i] > pivot) i++;
                while (ut[j] < pivot) j--;
                if (i <= j) {
                    long tu = ut[i]; ut[i] = ut[j]; ut[j] = tu;
                    int ti = it[i]; it[i] = it[j]; it[j] = ti;
                    i++; j--;
                }
            }
            if (j - lo < hi - i) { sortDescByUtil(it, ut, lo, j); lo = i; }
            else { sortDescByUtil(it, ut, i, hi); hi = j; }
        }
    }

    // ============================ PHASE 2: PARALLEL MINING ============================

    /** Projected database (read-only once built): passed as value between parent and child. */
    private static final class InstanceList {
        int[] sid; int[] pos; long[] pref; int size;
        InstanceList(int cap) {
            cap = Math.max(cap, 64);
            sid = new int[cap]; pos = new int[cap]; pref = new long[cap];
        }
        void add(int s, int p, long u) {
            if (size == sid.length) {
                int n = size << 1;
                sid = Arrays.copyOf(sid, n); pos = Arrays.copyOf(pos, n); pref = Arrays.copyOf(pref, n);
            }
            sid[size] = s; pos[size] = p; pref[size] = u; size++;
        }
    }

    /**
     * STRAT_STATIC: compute root-level candidates once, partition evenly into T blocks,
     * each mined inline by one task (no stealing, no dynamic splitting). Used for SC3
     * ablation to expose load imbalance from static partitioning.
     */
    private void runStaticRoot(InstanceList init) {
        MiningContext rc = acquireContext();
        int laStamp = (boundMode == BOUND_LA_PEU) ? ROOT_STAMP : 0;
        if (boundMode == BOUND_LA_PEU) computeBoundsLA(rc, init, laStamp);
        else computeBoundsPEU(rc, init);
        int nI = collectCandidates(rc, -1, true);
        int nS = collectCandidates(rc, -1, false);
        resetGlobalAccumulators(rc);
        int[] cI = Arrays.copyOf(rc.candBufI, nI);
        int[] cS = Arrays.copyOf(rc.candBufS, nS);
        releaseContext(rc);

        final int total = cI.length + cS.length;
        final int[] cand = new int[total];
        final boolean[] isI = new boolean[total];
        for (int k = 0; k < cI.length; k++) { cand[k] = cI[k]; isI[k] = true; }
        for (int k = 0; k < cS.length; k++) { cand[cI.length + k] = cS[k]; isI[cI.length + k] = false; }

        final int T = Math.max(1, numThreads);
        final int blk = (total + T - 1) / T;
        final List<RecursiveAction> blocks = new ArrayList<>();
        for (int b = 0; b < T; b++) {
            final int lo = b * blk;
            final int hi = Math.min(total, lo + blk);
            if (lo >= hi) break;
            blocks.add(new RecursiveAction() {
                @Override protected void compute() {
                    MiningContext ctx = acquireContext();
                    try {
                        for (int j = lo; j < hi; j++) {
                            ctx.patLen = 0;
                            processOne(ctx, init, cand[j], isI[j], -1); // canFork=false (STATIC): always inline
                        }
                    } finally { releaseContext(ctx); }
                }
            });
        }
        if (!blocks.isEmpty()) pool.invoke(new RecursiveAction() {
            @Override protected void compute() { invokeAll(blocks); }
        });
    }

    /** Root task: enumerate all promising items with an empty prefix. */
    private final class RootTask extends RecursiveAction {
        // Never serialised; declared only because the supertype is Serializable.
        private static final long serialVersionUID = 1L;
        final transient InstanceList init;
        RootTask(InstanceList init) { this.init = init; }
        @Override protected void compute() {
            MiningContext ctx = acquireContext();
            try {
                ctx.patLen = 0;
                mine(ctx, init, -1);
            } finally {
                releaseContext(ctx);
            }
        }
    }

    /**
     * Subtree mining task for one extension (parent + candidate). Carries a prefix snapshot
     * so the task is self-contained when stolen by another thread (work stealing).
     */
    private final class MineTask extends RecursiveAction {
        // Never serialised; declared only because the supertype is Serializable.
        private static final long serialVersionUID = 1L;
        final transient InstanceList parent;
        final int candidate;
        final boolean isIExt;
        final int lastItem;
        final int[] prefix;   // snapshot of the pattern at the parent node
        final int prefixLen;
        MineTask(InstanceList parent, int candidate, boolean isIExt, int lastItem, int[] prefix, int prefixLen) {
            this.parent = parent; this.candidate = candidate; this.isIExt = isIExt;
            this.lastItem = lastItem; this.prefix = prefix; this.prefixLen = prefixLen;
        }
        @Override protected void compute() {
            MiningContext ctx = acquireContext();
            try {
                // Restore the prefix into the borrowed context's buffer (self-contained after stealing)
                if (ctx.patBuf.length < prefixLen + 2) ctx.patBuf = Arrays.copyOf(ctx.patBuf, Math.max(ctx.patBuf.length * 2, prefixLen + 2));
                System.arraycopy(prefix, 0, ctx.patBuf, 0, prefixLen);
                ctx.patLen = prefixLen;
                processOne(ctx, parent, candidate, isIExt, lastItem);
            } finally {
                releaseContext(ctx);
            }
        }
    }

    /** Fork a subtask only when the pool has surplus capacity (RDLB). */
    private boolean shouldFork() {
        return ForkJoinTask.getSurplusQueuedTaskCount() <= forkSurplusThreshold;
    }

    /**
     * Parallel counterpart of processExtension() in the sequential engine. Uses ctx buffers
     * and LongAdder counters. Builds the projected database, computes utility/period,
     * records the pattern if qualifying, then calls mine() to continue (possibly forking).
     */
    private void processOne(MiningContext ctx, InstanceList parent, int candidate, boolean isIExt, int lastItem) {
        exploredNodesA.increment();
        checkMemory();
        InstanceList next = new InstanceList(256);
        long totalUtil = 0, totalRU = 0;
        int prevSid = -1, maxPeriod = 0;

        long[] evtMaxUtil = ctx.evtMaxUtil; int[] evtMaxPos = ctx.evtMaxPos;

        int i = 0;
        while (i < parent.size) {
            int s = parent.sid[i];
            if (useRegPruning) {
                // windowMode: the leading gap (prevSid == -1) is measured from the WINDOW's start, not
                // from the true predecessor occurrence in D_old -> it is not a real gap and must not prune.
                if (!(windowMode && prevSid == -1 && !windowIsSuffix)) {
                    int gap = (prevSid == -1) ? (s + 1) : (s - prevSid);
                    if (gap > maxRegularity) { prunedRegularityA.increment(); return; }
                }
            }
            int start = i;
            while (i < parent.size && parent.sid[i] == s) i++;
            int end = i;

            int evBase = seqEvtStart[s];
            int nEvents = seqEvtStart[s + 1] - evBase;
            for (int e = 0; e < nEvents; e++) evtMaxUtil[e] = -1L;
            boolean found = false;
            long maxUtilSeq = 0, ruFirst = 0;
            boolean first = true;

            for (int k = start; k < end; k++) {
                int p = parent.pos[k];
                long pref = parent.pref[k];
                int from, to;
                if (isIExt) {
                    if (p < 0) continue;
                    from = p + 1; to = evtPosStart[evBase + posEvent[p] + 1];
                } else {
                    from = (p < 0) ? seqPosStart[s] : evtPosStart[evBase + posEvent[p] + 1];
                    to = seqPosStart[s + 1];
                }
                for (int q = from; q < to; q++) {
                    if (items[q] == candidate) {
                        long nu = pref + utils[q];
                        int e = posEvent[q];
                        if (nu > evtMaxUtil[e]) {
                            evtMaxUtil[e] = nu; evtMaxPos[e] = q; found = true;
                            if (nu > maxUtilSeq) maxUtilSeq = nu;
                            if (first) { ruFirst = remUtils[q]; first = false; }
                        }
                        if (isIExt) break;
                        q = evtPosStart[evBase + e + 1] - 1;
                    }
                }
            }
            if (found) {
                for (int e = 0; e < nEvents; e++)
                    if (evtMaxUtil[e] >= 0) next.add(s, evtMaxPos[e], evtMaxUtil[e]);
                totalUtil += maxUtilSeq; totalRU += ruFirst;
                int per = (prevSid == -1) ? (s + 1) : (s - prevSid);
                if (per > maxPeriod) maxPeriod = per;
                prevSid = s;
            }
        }

        if (prevSid == -1) return;
        final boolean relaxed = seedMode || windowMode;   // boundary gaps are not final in these modes
        final int innerPeriod = maxPeriod;   // ADDED: gaps among occurrences, BEFORE the trailing gap
        int finalPer = numSeq - prevSid;
        // The trailing gap must not prune when seeding/windowing -- later data can shrink it.
        if (!relaxed && useRegPruning && finalPer > maxRegularity) { prunedRegularityA.increment(); return; }
        // The FLOOR may prune even then -- see floorPrune. Inner gaps are already pruned above, so
        // testing the trailing component here completes max(inner, trailing) > bound.
        if (relaxed && floorPrune && useRegPruning && finalPer > maxRegularity) { prunedRegularityA.increment(); return; }
        if (finalPer > maxPeriod) maxPeriod = finalPer;
        boolean isRegular = maxPeriod <= maxRegularity;

        pushPattern(ctx, candidate, isIExt);
        try {
            if ((relaxed || isRegular) && totalUtil >= minUtility)
                finalPatterns.put(formatCurrentPattern(ctx),
                        new PatternResult(totalUtil, maxPeriod, prevSid, innerPeriod));
            if (totalUtil + totalRU < minUtility) { prunedNodeCondA.increment(); return; }
            if (!relaxed && useRegPruning && !isRegular) { prunedRegularityA.increment(); return; }
            mine(ctx, next, candidate);
        } finally {
            popPattern(ctx, isIExt);
        }
    }

    /**
     * Parallel counterpart of expand() in the sequential engine. Computes bounds, filters
     * candidates, then for each candidate: FORK if the pool has surplus capacity (pushing
     * onto the local deque -- work stealing/RDLB), else recurse inline to retain cache
     * locality.
     */
    private void mine(MiningContext ctx, InstanceList projected, int lastItem) {
        if (abortHeapBytes > 0) checkHeapValve();
        if (projected.size == 0) return;
        long[] pr = (lastItem == -1 && ctx.patLen == 0) ? Prof.start() : null;

        int laStamp = 0;
        if (boundMode == BOUND_LA_PEU) {
            if (useEUCS && lastItem >= 0) {
                laStamp = ++ctx.laStampCounter;
                int d = itemToDense[lastItem];
                if (d >= 0) {
                    // laStampArr indexed by the active scheme; adjItem stores raw item ids
                    boolean dense = denseBuffers;
                    if (compactEucs) {
                        for (int k = adjIStart[d]; k < adjIStart[d + 1]; k++)
                            ctx.laStampArr[dense ? itemToDense[adjIItem[k]] : adjIItem[k]] = laStamp;
                        for (int k = adjSStart[d]; k < adjSStart[d + 1]; k++)
                            ctx.laStampArr[dense ? itemToDense[adjSItem[k]] : adjSItem[k]] = laStamp;
                        if (Prof.ON) eucsVisitedA.add(adjIStart[d + 1] - adjIStart[d] + adjSStart[d + 1] - adjSStart[d]);
                    } else {
                        int k = adjIStart[d];
                        for (; k < adjIStart[d + 1] && adjIUtil[k] >= minUtility; k++)
                            ctx.laStampArr[dense ? itemToDense[adjIItem[k]] : adjIItem[k]] = laStamp;
                        int k2 = adjSStart[d];
                        for (; k2 < adjSStart[d + 1] && adjSUtil[k2] >= minUtility; k2++)
                            ctx.laStampArr[dense ? itemToDense[adjSItem[k2]] : adjSItem[k2]] = laStamp;
                        if (Prof.ON) eucsVisitedA.add(Math.min(k + 1, adjIStart[d + 1]) - adjIStart[d]
                                + Math.min(k2 + 1, adjSStart[d + 1]) - adjSStart[d]);
                    }
                }
            } else {
                laStamp = ROOT_STAMP;
            }
        }

        if (boundMode == BOUND_LA_PEU) computeBoundsLA(ctx, projected, laStamp);
        else computeBoundsPEU(ctx, projected);

        int nI = collectCandidates(ctx, lastItem, true);
        int nS = collectCandidates(ctx, lastItem, false);
        resetGlobalAccumulators(ctx);

        int[] cI = Arrays.copyOf(ctx.candBufI, nI);
        int[] cS = Arrays.copyOf(ctx.candBufS, nS);
        Prof.stop("engine.mine.rootBounds", pr);

        // Fork decision based on ablation strategy (SC3):
        //   STATIC: no forking (static partition at root, subtree runs inline).
        //   STEAL:  fork only at the root level (lastItem==-1) for level-1 work stealing.
        //   RDLB:   fork at any level when the projected database is large enough.
        boolean canFork;
        if (parallelStrategy == STRAT_STATIC)      canFork = false;
        else if (parallelStrategy == STRAT_STEAL)  canFork = (lastItem == -1);
        else                                        canFork = projected.size >= minForkInstances;

        // Snapshot current prefix for tasks that may be stolen by other threads.
        List<MineTask> forked = null;
        for (int idx = 0; idx < cI.length; idx++) {
            if (canFork && shouldFork()) {
                int[] snap = Arrays.copyOf(ctx.patBuf, ctx.patLen);
                MineTask tk = new MineTask(projected, cI[idx], true, lastItem, snap, ctx.patLen);
                tk.fork();
                if (forked == null) forked = new ArrayList<>();
                forked.add(tk);
            } else {
                processOne(ctx, projected, cI[idx], true, lastItem);
            }
        }
        for (int idx = 0; idx < cS.length; idx++) {
            if (canFork && shouldFork()) {
                int[] snap = Arrays.copyOf(ctx.patBuf, ctx.patLen);
                MineTask tk = new MineTask(projected, cS[idx], false, lastItem, snap, ctx.patLen);
                tk.fork();
                if (forked == null) forked = new ArrayList<>();
                forked.add(tk);
            } else {
                processOne(ctx, projected, cS[idx], false, lastItem);
            }
        }
        if (forked != null) for (int k = forked.size() - 1; k >= 0; k--) forked.get(k).join();
    }

    // ============================ UPPER BOUNDS (using ctx buffers) ============================

    private void computeBoundsPEU(MiningContext ctx, InstanceList plist) {
        final boolean dense = denseBuffers;
        int i = 0;
        while (i < plist.size) {
            int s = plist.sid[i];
            int start = i;
            long maxPref = plist.pref[i];
            while (i < plist.size && plist.sid[i] == s) {
                if (plist.pref[i] > maxPref) maxPref = plist.pref[i];
                i++;
            }
            int end = i;
            int evBase = seqEvtStart[s];
            int seqEnd = seqPosStart[s + 1];

            for (int k = start; k < end; k++) {
                int p = plist.pos[k];
                if (p < 0) continue;
                int evEnd = evtPosStart[evBase + posEvent[p] + 1];
                for (int q = p + 1; q < evEnd; q++) {
                    int dd = itemToDense[items[q]];
                    if (dd < 0) continue; // item outside Tier 0 cannot become a candidate
                    int d = dense ? dd : items[q];
                    long b = maxPref + utils[q] + remUtils[q];
                    if (ctx.seqVisitI[d] != s) { ctx.seqVisitI[d] = s; ctx.seqActI[ctx.seqNumI++] = d; ctx.seqBoundI[d] = b; }
                    else if (b > ctx.seqBoundI[d]) ctx.seqBoundI[d] = b;
                }
            }
            int pMin = plist.pos[start];
            int from = (pMin < 0) ? seqPosStart[s] : evtPosStart[evBase + posEvent[pMin] + 1];
            for (int q = from; q < seqEnd; q++) {
                int dd = itemToDense[items[q]];
                if (dd < 0) continue;
                int d = dense ? dd : items[q];
                long b = maxPref + utils[q] + remUtils[q];
                if (ctx.seqVisitS[d] != s) { ctx.seqVisitS[d] = s; ctx.seqActS[ctx.seqNumS++] = d; ctx.seqBoundS[d] = b; }
                else if (b > ctx.seqBoundS[d]) ctx.seqBoundS[d] = b;
            }
            flushSeqBuffers(ctx, s);
        }
    }

    private void computeBoundsLA(MiningContext ctx, InstanceList plist, int laStamp) {
        final boolean dense = denseBuffers;
        int i = 0;
        while (i < plist.size) {
            int s = plist.sid[i];
            int start = i;
            while (i < plist.size && plist.sid[i] == s) i++;
            int end = i;
            int evBase = seqEvtStart[s];
            int seqEnd = seqPosStart[s + 1];

            for (int k = start; k < end; k++) {
                int p = plist.pos[k];
                long pref = plist.pref[k];
                long lar = 0;
                int sFrom = (p < 0) ? seqPosStart[s] : evtPosStart[evBase + posEvent[p] + 1];
                // Backward scan: accumulate look-ahead remaining (LAR) only for items in the
                // promising set (stamped at laStamp) -- tighter than standard remaining utility.
                for (int q = seqEnd - 1; q >= sFrom; q--) {
                    int dd = itemToDense[items[q]];
                    if (dd < 0) continue; // item outside Tier 0: not a candidate, not in C(alpha)
                    int d = dense ? dd : items[q];
                    long b = pref + utils[q] + lar;
                    if (ctx.seqVisitS[d] != s) { ctx.seqVisitS[d] = s; ctx.seqActS[ctx.seqNumS++] = d; ctx.seqBoundS[d] = b; }
                    else if (b > ctx.seqBoundS[d]) ctx.seqBoundS[d] = b;
                    if (ctx.laStampArr[d] == laStamp) lar += utils[q];
                }
                if (p >= 0) {
                    for (int q = sFrom - 1; q > p; q--) {
                        int dd = itemToDense[items[q]];
                        if (dd < 0) continue;
                        int d = dense ? dd : items[q];
                        long b = pref + utils[q] + lar;
                        if (ctx.seqVisitI[d] != s) { ctx.seqVisitI[d] = s; ctx.seqActI[ctx.seqNumI++] = d; ctx.seqBoundI[d] = b; }
                        else if (b > ctx.seqBoundI[d]) ctx.seqBoundI[d] = b;
                        if (ctx.laStampArr[d] == laStamp) lar += utils[q];
                    }
                }
            }
            flushSeqBuffers(ctx, s);
        }
    }

    private void flushSeqBuffers(MiningContext ctx, int s) {
        for (int k = 0; k < ctx.seqNumI; k++) {
            int it = ctx.seqActI[k];
            if (ctx.gMarkI[it] == -1) { ctx.gMarkI[it] = 1; ctx.gActI[ctx.gNumI++] = it; }
            ctx.gAccI[it] += ctx.seqBoundI[it];
            ctx.seqVisitI[it] = -1;
        }
        ctx.seqNumI = 0;
        for (int k = 0; k < ctx.seqNumS; k++) {
            int it = ctx.seqActS[k];
            if (ctx.gMarkS[it] == -1) { ctx.gMarkS[it] = 1; ctx.gActS[ctx.gNumS++] = it; }
            ctx.gAccS[it] += ctx.seqBoundS[it];
            ctx.seqVisitS[it] = -1;
        }
        ctx.seqNumS = 0;
    }

    private void resetGlobalAccumulators(MiningContext ctx) {
        for (int k = 0; k < ctx.gNumI; k++) { int it = ctx.gActI[k]; ctx.gAccI[it] = 0; ctx.gMarkI[it] = -1; }
        ctx.gNumI = 0;
        for (int k = 0; k < ctx.gNumS; k++) { int it = ctx.gActS[k]; ctx.gAccS[it] = 0; ctx.gMarkS[it] = -1; }
        ctx.gNumS = 0;
    }

    private int collectCandidates(MiningContext ctx, int lastItem, boolean iExt) {
        int[] out = iExt ? ctx.candBufI : ctx.candBufS;
        long[] acc = iExt ? ctx.gAccI : ctx.gAccS;
        int[] mark = iExt ? ctx.gMarkI : ctx.gMarkS;
        int n = 0;

        if (useEUCS && lastItem >= 0) {
            int d = itemToDense[lastItem];
            if (d < 0) return 0;
            int[] adjStart = iExt ? adjIStart : adjSStart;
            int[] adjItem = iExt ? adjIItem : adjSItem;
            long[] adjUtil = iExt ? adjIUtil : adjSUtil;
            if (compactEucs) {
                // Counters over the extension items that OCCUR in the projection (they carry a bound): those
                // absent from the adjacency slice are pruned by co-occurrence, the rest by the bound or kept.
                // This reading does not depend on how the structure was built.
                int occurring = iExt ? ctx.gNumI : ctx.gNumS, found = 0;
                if (Prof.ON) eucsVisitedA.add(adjStart[d + 1] - adjStart[d]);
                for (int k = adjStart[d]; k < adjStart[d + 1]; k++) {
                    int it = adjItem[k];
                    int d2 = itemToDense[it];
                    int idx = denseBuffers ? d2 : it;
                    if (d2 < 0 || mark[idx] == -1) continue;      // does not occur after the prefix here
                    found++;
                    if (boundMode != BOUND_NONE && acc[idx] < minUtility) prunedBoundA.increment();
                    else out[n++] = it;
                }
                candidateCountA.add(occurring);
                prunedEucsA.add(occurring - found);
                return n;
            }
            for (int k = adjStart[d]; k < adjStart[d + 1]; k++) {
                candidateCountA.increment();
                if (Prof.ON) eucsVisitedA.increment();
                if (adjUtil[k] < minUtility) {
                    // Adjacency list is sorted descending: all remaining entries are also below
                    // minUtility, so break early and count all as pruned by EUCS.
                    int remaining = adjStart[d + 1] - k;
                    prunedEucsA.add(remaining);
                    candidateCountA.add(remaining - 1);
                    break;
                }
                int it = adjItem[k];                 // raw item id
                if (Prof.ON && iExt && it < lastItem) eucsDeadReverseA.increment();
                int d2 = itemToDense[it];            // dense id (for bound lookup when denseBuffers)
                int idx = denseBuffers ? d2 : it;    // buffer index under the active scheme
                long bound = (d2 >= 0 && mark[idx] != -1) ? acc[idx] : 0;
                if (boundMode != BOUND_NONE && bound < minUtility) prunedBoundA.increment();
                else out[n++] = it;                  // emit raw item id for processOne
            }
        } else {
            int[] act = iExt ? ctx.gActI : ctx.gActS;
            int cnt = iExt ? ctx.gNumI : ctx.gNumS;
            boolean dense = denseBuffers;
            for (int k = 0; k < cnt; k++) {
                int idx = act[k];                    // buffer index (dense id or raw item)
                candidateCountA.increment();
                if (boundMode != BOUND_NONE && acc[idx] < minUtility) prunedBoundA.increment();
                else out[n++] = dense ? denseToItem[idx] : idx;  // emit raw item id
            }
            Arrays.sort(out, 0, n); // deterministic exploration order
        }
        return n;
    }

    // ============================ PATTERN BUFFER (per ctx) ============================

    private void pushPattern(MiningContext ctx, int item, boolean iExt) {
        if (ctx.patLen + 2 > ctx.patBuf.length) ctx.patBuf = Arrays.copyOf(ctx.patBuf, ctx.patBuf.length * 2);
        if (!iExt && ctx.patLen > 0) ctx.patBuf[ctx.patLen++] = -1;
        ctx.patBuf[ctx.patLen++] = item;
    }

    private void popPattern(MiningContext ctx, boolean iExt) {
        ctx.patLen--;
        if (!iExt && ctx.patLen > 0) ctx.patLen--;
    }

    private String formatCurrentPattern(MiningContext ctx) {
        StringBuilder sb = new StringBuilder("<(");
        for (int k = 0; k < ctx.patLen; k++) {
            if (ctx.patBuf[k] == -1) sb.append(")(");
            else {
                if (k > 0 && ctx.patBuf[k - 1] != -1) sb.append(' ');
                sb.append(ctx.patBuf[k]);
            }
        }
        return sb.append(")>").toString();
    }

    // ============================ DATA LOADING ============================

    private void loadData(String dataPath, String utilPath, double minRatio, double maxRegRatio) throws IOException {
        Map<Integer, Integer> externalUtility = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(utilPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(":");
                if (parts.length >= 2)
                    externalUtility.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            }
        }

        List<int[]> seqItemsList = new ArrayList<>();
        List<long[]> seqUtilsList = new ArrayList<>();
        List<int[]> seqEvtLenList = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(dataPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] eventStrs = line.split("-1");
                int[] tmpItems = new int[64]; long[] tmpUtils = new long[64];
                int[] tmpEvtLen = new int[16];
                int nPos = 0, nEvt = 0;
                for (String eventStr : eventStrs) {
                    eventStr = eventStr.replace("-2", "").trim();
                    if (eventStr.isEmpty()) continue;
                    String[] tokens = eventStr.split("\\s+");
                    int evStart = nPos;
                    for (String tk : tokens) {
                        int open = tk.indexOf('['), close = tk.indexOf(']');
                        if (open == -1 || close == -1) continue;
                        try {
                            int itemId = Integer.parseInt(tk.substring(0, open).trim());
                            int qty = Integer.parseInt(tk.substring(open + 1, close).trim());
                            long u = (long) qty * externalUtility.getOrDefault(itemId, 0);
                            if (itemId > maxItemId) maxItemId = itemId;
                            if (nPos == tmpItems.length) {
                                tmpItems = Arrays.copyOf(tmpItems, nPos * 2);
                                tmpUtils = Arrays.copyOf(tmpUtils, nPos * 2);
                            }
                            tmpItems[nPos] = itemId; tmpUtils[nPos] = u; nPos++;
                        } catch (NumberFormatException ignored) {}
                    }
                    int evLen = nPos - evStart;
                    if (evLen > 0) {
                        insertionSortPaired(tmpItems, tmpUtils, evStart, nPos - 1);
                        if (nEvt == tmpEvtLen.length) tmpEvtLen = Arrays.copyOf(tmpEvtLen, nEvt * 2);
                        tmpEvtLen[nEvt++] = evLen;
                    }
                }
                if (nPos > 0) {
                    seqItemsList.add(Arrays.copyOf(tmpItems, nPos));
                    seqUtilsList.add(Arrays.copyOf(tmpUtils, nPos));
                    seqEvtLenList.add(Arrays.copyOf(tmpEvtLen, nEvt));
                }
            }
        }

        buildCsr(seqItemsList, seqUtilsList, seqEvtLenList, minRatio, maxRegRatio);
    }

    /** Shared CSR construction + threshold computation (extracted verbatim from {@link #loadData}). */
    private void buildCsr(List<int[]> seqItemsList, List<long[]> seqUtilsList, List<int[]> seqEvtLenList,
                          double minRatio, double maxRegRatio) {
        numSeq = seqItemsList.size();
        int totalPos = 0, totalEvt = 0;
        for (int s = 0; s < numSeq; s++) {
            totalPos += seqItemsList.get(s).length;
            totalEvt += seqEvtLenList.get(s).length;
            maxEventsPerSeq = Math.max(maxEventsPerSeq, seqEvtLenList.get(s).length);
        }
        seqPosStart = new int[numSeq + 1];
        seqEvtStart = new int[numSeq + 1];
        evtPosStart = new int[totalEvt + 1];
        items = new int[totalPos];
        utils = new long[totalPos];
        remUtils = new long[totalPos];
        posEvent = new int[totalPos];
        seqUtility = new long[numSeq];

        int pCur = 0, eCur = 0;
        for (int s = 0; s < numSeq; s++) {
            seqPosStart[s] = pCur; seqEvtStart[s] = eCur;
            int[] its = seqItemsList.get(s);
            long[] uts = seqUtilsList.get(s);
            int[] evl = seqEvtLenList.get(s);
            long su = 0;
            int local = 0, e = 0, inEvt = 0;
            for (int j = 0; j < its.length; j++) {
                if (inEvt == 0) evtPosStart[eCur + e] = pCur + j;
                items[pCur + j] = its[j]; utils[pCur + j] = uts[j];
                posEvent[pCur + j] = e;
                su += uts[j];
                inEvt++;
                if (inEvt == evl[e]) { e++; inEvt = 0; }
                local++;
            }
            long acc = 0;
            for (int j = its.length - 1; j >= 0; j--) {
                remUtils[pCur + j] = acc;
                acc += uts[j];
            }
            seqUtility[s] = su;
            totalDBUtility += su;
            pCur += local; eCur += evl.length;
        }
        seqPosStart[numSeq] = pCur;
        seqEvtStart[numSeq] = eCur;
        evtPosStart[totalEvt] = pCur;

        minUtility = (forcedMinUtil >= 0) ? forcedMinUtil : (long) Math.ceil(totalDBUtility * minRatio);
        maxRegularity = (forcedMaxReg >= 0) ? forcedMaxReg : (int) (numSeq * maxRegRatio);
    }

    private static void insertionSortPaired(int[] keys, long[] vals, int lo, int hi) {
        for (int i = lo + 1; i <= hi; i++) {
            int k = keys[i]; long v = vals[i];
            int j = i - 1;
            while (j >= lo && keys[j] > k) { keys[j + 1] = keys[j]; vals[j + 1] = vals[j]; j--; }
            keys[j + 1] = k; vals[j + 1] = v;
        }
    }

    // ============================ UTILITIES ============================

    private void savePatternsToFile(String path) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            for (Map.Entry<String, PatternResult> e : finalPatterns.entrySet())
                w.write(e.getKey() + " #UTIL: " + e.getValue().utility + " #PER: " + e.getValue().periodicity + "\n");
        }
    }

    private static String trimNum(double v) {
        String s = String.valueOf(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Check for interruption or deadline; memory tracking is now handled by MemMeter.peakBytes(). */
    private void checkMemory() {
        if (Thread.currentThread().isInterrupted()) throw new RuntimeException("TIMEOUT_INTERRUPT");
        if (System.currentTimeMillis() > deadlineMillis) throw new RuntimeException("TIMEOUT_INTERRUPT");
    }

    private void startMemorySampler() {
        stopMemorySampler();
        samplerRunning = true;
        memorySampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (samplerRunning) {
                long cur = rt.totalMemory() - rt.freeMemory();
                if (cur > maxMemory) maxMemory = cur;
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        }, "rhuspm-par-mem-sampler");
        memorySampler.setDaemon(true);
        memorySampler.start();
    }

    private void stopMemorySampler() {
        samplerRunning = false;
        if (memorySampler != null) {
            memorySampler.interrupt();
            try { memorySampler.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            memorySampler = null;
        }
    }

    private void forceGC() {
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Graceful pool shutdown: wait briefly then force-interrupt workers to avoid contaminating the next run. */
    private static void shutdownPool(ForkJoinPool p) {
        if (p == null) return;
        p.shutdown();
        try {
            if (!p.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.shutdownNow();
                p.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            p.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public double getPeakMemoryMB() { return maxMemory / (1024.0 * 1024.0); }
    public int getPatternCount() { return finalPatterns.size(); }
    public long getRuntimeMs() { return endTimestamp - startTimestamp; }
    public long getMinUtility() { return minUtility; }
    public int getNumValid() { return numValid; }
    public int getMaxRegularity() { return maxRegularity; }
    public long getCpuTimeMs() {
        return (cpuStartNanos < 0 || cpuEndNanos < 0) ? -1L : (cpuEndNanos - cpuStartNanos) / 1_000_000L;
    }

    public String configDescription() {
        return String.format("EUCS=%s, bound=%s, regPruning=%s, threads=%d",
                useEUCS, boundMode == BOUND_NONE ? "NONE" : boundMode == BOUND_PEU ? "PEU" : "LA_PEU",
                useRegPruning, numThreads);
    }

    public void printStatistics() {
        System.out.println("\n========== " + label + " (" + configDescription() + ") ==========");
        System.out.println("Patterns found              : " + finalPatterns.size());
        System.out.println("Runtime (ms)                : " + getRuntimeMs() + " (preprocessing: " + preprocessMs + ")");
        System.out.println("Peak heap memory            : " + String.format("%.2f MB", getPeakMemoryMB()));
        System.out.println("Candidates generated        : " + candidateCount);
        System.out.println("Nodes explored              : " + exploredNodes);
        System.out.println("Pruned by EUCS (Tier 1)     : " + prunedEucs);
        System.out.println("Pruned by upper bound (T2)  : " + prunedBound);
        System.out.println("Pruned by node condition    : " + prunedNodeCond);
        System.out.println("Pruned by regularity (T3)   : " + prunedRegularity);
        System.out.println("===========================================================");
    }
}
