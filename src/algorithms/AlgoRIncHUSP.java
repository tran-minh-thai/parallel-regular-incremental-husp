package algorithms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>RIncHusp — Regular Incremental High-Utility Sequential Pattern miner</h1>
 *
 * Direct competing BASELINE: Ishita, Ahmed &amp; Leung (2022), "New approaches for mining
 * regular high utility sequential patterns", Applied Intelligence — bib key
 * {@code Ishita2022}. Corresponds to the <b>"RIncHusp"</b> row in the baseline table
 * (experimentVN.tex): R/I/P = yes/yes/no, re-implementation, original is APPROXIMATE. This
 * implementation references {@code codeThamKhao/AlgoRIncHUSP.java} and {@code AlgoRIncHUSP_Adaptive.java}.
 * <p>
 * Initialize via one SEQUENTIAL static mining pass over {@code D_old} (through {@link AlgoRHUSP})
 * to build the prefix enumeration tree; each later batch only TRAVERSES EXISTING NODES of the tree
 * to accumulate utility and update regularity ({@link #updateNodes}).
 * <p>
 * This baseline ISOLATES exactly ONE inherent defect of RIncHusp that {@link AlgoPRIncHUSP} fixes:
 * <ul>
 *   <li><b>NO node generation for emerging patterns</b> (seed-once on {@code D_old}, only updates old nodes)
 *       -> misses patterns that only become regular/high-utility in later batches.</li>
 * </ul>
 * For fairness (not turning the baseline into a strawman), utility is updated EXACTLY by the
 * <b>max-measure</b>: for each new sequence, every existing pattern is incremented by {@code u(α,S)}
 * = the occurrence yielding the largest utility in that sequence ({@link #evalSequence}). Since
 * {@code u(α,DB)=Σ_S u(α,S)} is additive over sequences, this incremental update yields the EXACT
 * value of a re-mine — unlike the APPROXIMATE "raw accumulation" version (adding only single-item
 * utility) of {@code codeThamKhao/AlgoRIncHUSP.java}. Thus the gap against P-RIncHUSP reflects the
 * TRUE contribution of "new-pattern mining + parallelism + adaptive buffer", not a utility-measurement error.
 * <p>
 * Runs SEQUENTIALLY. The buffer uses a FIXED threshold θ=μ·minUtil (μ={@link #bufferFactor}, NOT adaptive —
 * adaptivity is a contribution exclusive to P-RIncHUSP).
 */
public class AlgoRIncHUSP implements IncrementalHUSPMiner {

    private static final class Node {
        final int id;
        long utility; int maxReg = 0; int lastSeqId = -1;
        final Map<Integer, Node> iChildren = new HashMap<>();
        final Map<Integer, Node> sChildren = new HashMap<>();
        Node(int id) { this.id = id; }
    }

    private int nodeCounter = 1;
    private final Node root = new Node(0);
    private Node newNode() { return new Node(nodeCounter++); }
    private final List<List<int[]>> db = new ArrayList<>();
    private final AlgoRHUSP staticMiner = new AlgoRHUSP();

    /**
     * FIXED buffer factor of the original RIncHusp (θ = bufferFactor · minUtil); NOT adaptive.
     * MUST equal {@code AdaptiveBuffer.bufferFactorMin} (the initial μ of P-RIncHUSP) for a fair comparison.
     */
    public double bufferFactor = 0.4;

    private double minUtilRatio, maxRegRatio;
    private long minUtil, totalUD;
    private int numSequences, maxReg;
    private double bufferThreshold;
    private boolean initialized = false;

    private final Map<String, long[]> highUtility = new HashMap<>();
    private int bufferedCount = 0;
    private double peakMB = 0;

    public AlgoRIncHUSP() { staticMiner.parallel = false; }

    @Override public String name() { return "RIncHusp"; }

    @Override
    public void initialBuild(List<List<int[]>> dOld, double minUtilRatio, double maxRegRatio) {
        this.minUtilRatio = minUtilRatio; this.maxRegRatio = maxRegRatio;
        db.addAll(dOld); numSequences = db.size();
        recomputeThresholds();
        // Initial seeding mines statically at the BUFFER THRESHOLD θ = bufferFactor·minUtil (NOT
        // minUtil): retains promising patterns in [θ, minUtil) so they can be promoted to HS in
        // later batches — matching RIncHusp's buffer mechanism. Mining at minUtil would discard them.
        long seedThreshold = (long) Math.ceil(bufferFactor * minUtil);
        Map<String, long[]> seeds = staticMiner.mine(db, seedThreshold, maxReg);
        for (Map.Entry<String, long[]> e : seeds.entrySet())
            insertPath(parse(e.getKey()), e.getValue()[0], (int) e.getValue()[1], numSequences - 1);
        initialized = true;
        classify();
        sampleMemory();
    }

    @Override
    public long processBatch(List<List<int[]>> deltaD) {
        long t0 = System.currentTimeMillis();
        if (!initialized) { initialBuild(deltaD, minUtilRatio, maxRegRatio); return System.currentTimeMillis() - t0; }

        int startSid = numSequences;
        db.addAll(deltaD); numSequences = db.size();
        recomputeThresholds();

        // FIXED buffer threshold of the original RIncHusp (approximate, not adaptive per batch)
        bufferThreshold = bufferFactor * minUtil;

        // update ONLY existing nodes (no new-pattern generation — the inherent defect is isolated),
        // but compute utility EXACTLY by the max-measure (see evalSequence)
        for (int i = 0; i < deltaD.size(); i++)
            evalSequence(deltaD.get(i), startSid + i);

        classify();
        sampleMemory();
        return System.currentTimeMillis() - t0;
    }

    /**
     * Update the enumeration tree with ONE new sequence in the EXACT manner of the original RIncHusp
     * (Ishita2022 maintains a utility-list): for each existing pattern α matched in sequence
     * {@code seq}, add to the node's utility the value {@code u(α, seq)} = the occurrence yielding
     * the LARGEST UTILITY (max-measure) in that sequence, and update regularity. Since
     * {@code u(α,DB) = Σ_S u(α,S)} is additive over sequences, this incremental update yields the
     * EXACT utility of a re-mine. NO new nodes are created (seed-once).
     * <p>
     * To obtain the correct max-measure (not a greedy "first-match"), traverse EVERY matching
     * position and record the LARGEST prefix utility for each node in this sequence; prune dominated
     * states {@code (node, itemset, item)} to keep polynomial complexity.
     */
    private void evalSequence(List<int[]> seq, int seqId) {
        Map<Node, Long> best = new HashMap<>();   // matched node -> max-measure utility in this sequence
        Map<Long, Long> memo = new HashMap<>();   // (node,itemset,item) -> largest utility reached (dominance pruning)
        // start: s-children of the root may match at ANY position
        for (Map.Entry<Integer, Node> en : root.sChildren.entrySet()) {
            for (int e = 0; e < seq.size(); e++) {
                int[] ev = seq.get(e);
                for (int k = 0; k < ev.length / 2; k++)
                    if (ev[k * 2] == en.getKey())
                        explore(en.getValue(), seq, e, k, ev[k * 2 + 1], best, memo);
            }
        }
        // apply: add max-measure utility + update regularity for each matched node
        for (Map.Entry<Node, Long> en : best.entrySet()) {
            Node n = en.getKey();
            n.utility += en.getValue();
            int period = (n.lastSeqId == -1) ? (seqId + 1) : (seqId - n.lastSeqId);
            if (period > n.maxReg) n.maxReg = period;
            n.lastSeqId = seqId;
        }
    }

    /** Matched the path to {@code node}, last item at {@code (eIdx,iIdx)}, accumulated utility {@code acc}. */
    private void explore(Node node, List<int[]> seq, int eIdx, int iIdx, long acc,
                         Map<Node, Long> best, Map<Long, Long> memo) {
        long state = ((long) node.id << 32) | ((long) eIdx << 16) | iIdx;
        Long prev = memo.get(state);
        if (prev != null && prev >= acc) return;        // dominated state -> prune (utility is non-negative)
        memo.put(state, acc);
        Long b = best.get(node);
        if (b == null || acc > b) best.put(node, acc);
        // i-children: SAME itemset eIdx, item index > iIdx
        int[] ev = seq.get(eIdx);
        if (!node.iChildren.isEmpty())
            for (int k = iIdx + 1; k < ev.length / 2; k++) {
                Node c = node.iChildren.get(ev[k * 2]);
                if (c != null) explore(c, seq, eIdx, k, acc + ev[k * 2 + 1], best, memo);
            }
        // s-children: itemsets > eIdx, any item position
        if (!node.sChildren.isEmpty())
            for (int e = eIdx + 1; e < seq.size(); e++) {
                int[] ev2 = seq.get(e);
                for (int k = 0; k < ev2.length / 2; k++) {
                    Node c = node.sChildren.get(ev2[k * 2]);
                    if (c != null) explore(c, seq, e, k, acc + ev2[k * 2 + 1], best, memo);
                }
            }
    }

    private void insertPath(List<int[]> path, long util, int reg, int lastSeqId) {
        Node cur = root;
        for (int[] step : path) {                       // step = {extType, item}
            Map<Integer, Node> ch = (step[0] == PatternTree.I_EXT) ? cur.iChildren : cur.sChildren;
            cur = ch.computeIfAbsent(step[1], k -> newNode());
        }
        cur.utility = util; cur.maxReg = reg; cur.lastSeqId = lastSeqId;
    }

    private void classify() {
        highUtility.clear(); bufferedCount = 0;
        dfsClassify(root, new ArrayList<>());
    }

    private void dfsClassify(Node n, List<int[]> path) {
        if (!path.isEmpty()) {
            int finalPer = (n.lastSeqId == -1) ? numSequences : (numSequences - n.lastSeqId);
            int trueMaxReg = Math.max(n.maxReg, finalPer);
            if (trueMaxReg <= maxReg) {
                if (n.utility >= minUtil) highUtility.put(pathStr(path), new long[]{n.utility, trueMaxReg});
                else if (n.utility >= bufferThreshold) bufferedCount++;
            }
        }
        for (Map.Entry<Integer, Node> e : n.sChildren.entrySet()) {
            path.add(new int[]{PatternTree.S_EXT, e.getKey()});
            dfsClassify(e.getValue(), path); path.remove(path.size() - 1);
        }
        for (Map.Entry<Integer, Node> e : n.iChildren.entrySet()) {
            path.add(new int[]{PatternTree.I_EXT, e.getKey()});
            dfsClassify(e.getValue(), path); path.remove(path.size() - 1);
        }
    }

    private void recomputeThresholds() {
        totalUD = 0;
        for (List<int[]> seq : db)
            for (int[] ev : seq)
                for (int k = 1; k < ev.length; k += 2) totalUD += ev[k];
        minUtil = (long) Math.ceil(minUtilRatio * totalUD);
        maxReg = (int) (maxRegRatio * numSequences);
        if (bufferThreshold == 0) bufferThreshold = bufferFactor * minUtil;
    }

    // ---- convert pattern string <-> path ----
    private static List<int[]> parse(String patStr) {
        List<int[]> path = new ArrayList<>();
        String clean = patStr.replace("<", "").replace(">", "");
        String[] events = clean.replace(")(", "#").replace("(", "").replace(")", "").split("#");
        boolean firstEvent = true;
        for (String ev : events) {
            String t = ev.trim();
            if (t.isEmpty()) continue;
            String[] toks = t.split("[,\\s]+");
            boolean firstItem = true;
            for (String tok : toks) {
                try {
                    int item = Integer.parseInt(tok);
                    int extType = firstItem ? (firstEvent ? PatternTree.S_EXT : PatternTree.S_EXT)
                                            : PatternTree.I_EXT;
                    path.add(new int[]{extType, item});
                    firstItem = false;
                } catch (Exception ignored) {}
            }
            firstEvent = false;
        }
        return path;
    }

    private static String pathStr(List<int[]> path) {
        StringBuilder sb = new StringBuilder("<(");
        boolean firstInEvent = true;
        for (int i = 0; i < path.size(); i++) {
            int[] step = path.get(i);
            if (i > 0 && step[0] == PatternTree.S_EXT) { sb.append(")("); firstInEvent = true; }
            if (!firstInEvent) sb.append(' ');
            sb.append(step[1]); firstInEvent = false;
        }
        return sb.append(")>").toString();
    }

    private void sampleMemory() {
        Runtime rt = Runtime.getRuntime();
        double mb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        if (mb > peakMB) peakMB = mb;
    }

    @Override public Map<String, long[]> getHighUtilityPatterns() { return highUtility; }
    @Override public int bufferedCount() { return bufferedCount; }
    @Override public double peakMemoryMB() { return peakMB; }
}
