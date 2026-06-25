package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Reference baseline: IncUSP-Miner+ (ACM TIST 2018)
 * TSU bounding implemented exactly per Definition 16 (2-case condition).
 * - Tracks candidateCount (number of candidate nodes generated).
 * - Tracks prunedTSU (number of candidates pruned by the TSU bound).
 */
public class IncUSPMinerPlusAlgorithm {

    public double Su;
    public double currentMinUtil;
    public int maxDepth = 1000; // Safety net against stack overflow only — does not change baseline logic

    // ========================================================================
    // EXPERIMENTAL METRIC VARIABLES (Metrics)
    // ========================================================================
    public int patternCount = 0;
    public long candidateCount = 0;
    public long exploredNodes = 0; // Patterns ACTUALLY expanded (recursed into) — measures pruning effectiveness
    public long prunedTSU = 0;

    public Map<Pattern, Double> largeMap = new HashMap<>();

    public IncUSPMinerPlusAlgorithm(double Su) {
        this.Su = Su;
    }

    // ========================================================================
    // INCUSP-MINER+ DATA STRUCTURES
    // ========================================================================
    static class Instance {
        int itemsetIdx, itemIdx;
        double utility;

        public Instance(int is, int ii, double u) {
            itemsetIdx = is;
            itemIdx = ii;
            utility = u;
        }
    }

    static class Tracker {
        int seqId;
        List<Instance> instances = new ArrayList<>();

        double exactUtility = 0; // u(alpha, s) - best utility
        double peu = 0;          // PEU(alpha, s)
        double tsu = 0;          // TSU of this tracker (computed per Definition 16)

        public Tracker(int id) {
            this.seqId = id;
        }

        public void addInstance(Instance inst, Sequence seq) {
            instances.add(inst);
            // Update exactUtility
            if (inst.utility > exactUtility) exactUtility = inst.utility;

            // Update PEU for this prefix (fallback for TSU case 2)
            double currentPeu = inst.utility + seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
            if (currentPeu > peu) peu = currentPeu;
        }

        /**
         * Exact Definition 16 from the IncUSP-Miner+ paper.
         */
        public void finalizeTSU(Tracker parentTracker, Sequence seq) {
            if (instances.isEmpty()) {
                this.tsu = 0;
                return;
            }

            // 1-item case (Definition 15)
            if (parentTracker == null) {
                Instance firstInst = instances.get(0);
                this.tsu = firstInst.utility + seq.getRestUtility(firstInst.itemsetIdx, firstInst.itemIdx);
            }
            // l-sequence case (Definition 16: 2-case branching)
            else {
                Instance parentFirstInst = parentTracker.instances.get(0);

                // Test: is the prefix's first position the one yielding the maximum utility?
                // epsilon 1e-6 to avoid floating-point error in Java
                if (Math.abs(parentTracker.exactUtility - parentFirstInst.utility) < 1e-6) {
                    // Case 1: TSU = u(alpha, a_p1, s) + u(i, p1, s) + remainingUtility(i, p1, s)
                    // (Note: instances.get(0).utility is exactly the sum u(alpha, a_p1) + u(i, p1))
                    Instance firstInst = instances.get(0);
                    this.tsu = firstInst.utility + seq.getRestUtility(firstInst.itemsetIdx, firstInst.itemIdx);
                } else {
                    // Case 2: fall back to the parent's PEU
                    this.tsu = parentTracker.peu;
                }
            }
        }
    }

    static class TrackerList {
        Map<Integer, Tracker> trackers = new HashMap<>();

        public Tracker getTracker(int seqId) {
            return trackers.computeIfAbsent(seqId, k -> new Tracker(seqId));
        }
    }

    static class CPTreeNode {
        Pattern pattern;
        TrackerList trackerList;
        double exactUtilityD = 0;
        double tsuD = 0;
        Map<Integer, CPTreeNode> iChildren = new HashMap<>();
        Map<Integer, CPTreeNode> sChildren = new HashMap<>();

        public CPTreeNode(Pattern p) {
            this.pattern = p;
            this.trackerList = new TrackerList();
        }
    }

    CPTreeNode root;
    Map<Integer, Sequence> globalDB = new HashMap<>();

    /**
     * Release the heavy structures (CP-tree + globalDB) IMMEDIATELY when the algorithm
     * cannot continue (OOM/poison in the incremental phase). The IncUSP CP-tree accumulates
     * across batches -> it can occupy several GB; if not released at once, subsequent
     * main-thread allocations (log/snapshot) keep hitting OOM. After calling this, the
     * caller should forceFullGC to reclaim heap.
     */
    public void releaseState() {
        root = null;
        if (globalDB != null) globalDB.clear();
        if (largeMap != null) largeMap.clear();
    }

    // ========================================================================
    // ALGORITHM CORE (INITIAL AND INCREMENTAL)
    // ========================================================================
    public void runInitialMining(List<Sequence> dInit, double currentTotalUtility) {
        // Reset state BEFORE starting so 1-item candidates are counted in full.
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedTSU = 0;
        largeMap.clear();
        globalDB.clear();

        this.currentMinUtil = currentTotalUtility * Su;
        for (Sequence seq : dInit) globalDB.put(seq.id, seq);

        root = new CPTreeNode(new Pattern());

        Map<Integer, TrackerList> l1Trackers = new HashMap<>();
        for (Sequence seq : dInit) {
            for (int is = 0; is < seq.itemsets.size(); is++) {
                QItemset itemset = seq.itemsets.get(is);
                for (int ii = 0; ii < itemset.items.size(); ii++) {
                    QItem item = itemset.items.get(ii);
                    TrackerList tl = l1Trackers.computeIfAbsent(item.id, k -> new TrackerList());
                    tl.getTracker(seq.id).addInstance(new Instance(is, ii, item.utility), seq);
                }
            }
        }

        // FAIR COUNTING (generation counts): EVERY 1-item generated, BEFORE filtering by TSU.
        candidateCount += l1Trackers.size();

        // Finalize TSU for the 1-item elements
        for (TrackerList tl : l1Trackers.values()) {
            for (Tracker t : tl.trackers.values()) {
                t.finalizeTSU(null, globalDB.get(t.seqId));
            }
        }

        for (Map.Entry<Integer, TrackerList> entry : l1Trackers.entrySet()) {
            TrackerList tl = entry.getValue();
            double aggTsu = 0;
            for (Tracker t : tl.trackers.values()) aggTsu += t.tsu;
            if (aggTsu < currentMinUtil) {
                prunedTSU++; // Drop the 1-item immediately, do not store in the CP-tree
                continue;
            }
            Pattern p = new Pattern(entry.getKey());
            CPTreeNode child = new CPTreeNode(p);
            child.trackerList = tl;
            root.sChildren.put(entry.getKey(), child);

            expandNode(child, dInit);
        }

        countHUSP(root);
    }

    public void runIncrementalMining(List<Sequence> deltaD, List<Sequence> dSoFar, double newTotalUtility) {
        this.currentMinUtil = newTotalUtility * Su;
        for (Sequence seq : deltaD) globalDB.put(seq.id, seq);

        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedTSU = 0;
        largeMap.clear();

        Map<Integer, TrackerList> deltaL1Trackers = new HashMap<>();
        for (Sequence seq : deltaD) {
            for (int is = 0; is < seq.itemsets.size(); is++) {
                QItemset itemset = seq.itemsets.get(is);
                for (int ii = 0; ii < itemset.items.size(); ii++) {
                    QItem item = itemset.items.get(ii);
                    TrackerList tl = deltaL1Trackers.computeIfAbsent(item.id, k -> new TrackerList());
                    tl.getTracker(seq.id).addInstance(new Instance(is, ii, item.utility), seq);
                }
            }
        }

        // FAIR COUNTING (generation counts): EVERY 1-item in the incremental batch generated, BEFORE filtering by TSU.
        candidateCount += deltaL1Trackers.size();

        // Finalize TSU for the 1-items in deltaD
        for (TrackerList tl : deltaL1Trackers.values()) {
            for (Tracker t : tl.trackers.values()) {
                t.finalizeTSU(null, globalDB.get(t.seqId));
            }
        }

        for (Map.Entry<Integer, TrackerList> entry : deltaL1Trackers.entrySet()) {
            int item = entry.getKey();
            CPTreeNode child = root.sChildren.get(item);

            if (child == null) {
                Pattern p = new Pattern(item);
                child = new CPTreeNode(p);
                root.sChildren.put(item, child);
            }
            updateAndExpandNode(child, entry.getValue(), deltaD);
        }

        countHUSP(root);
    }

    // ========================================================================
    // RECURSIVE FUNCTION (EXPANSION AND TSU PRUNING)
    // ========================================================================
    private void expandNode(CPTreeNode node, List<Sequence> db) {
        expandNode(node, db, 1);
    }

    private void expandNode(CPTreeNode node, List<Sequence> db, int depth) {
        if (depth > maxDepth) return;
        // MEASUREMENT DEFENSE: stop IMMEDIATELY when the runner cancels (timeout) -> avoid an
        // orphan thread continuing to consume CPU / hold heap and CONTAMINATING the next run's
        // time/memory. Only triggers once timed out (result discarded) -> does NOT change the
        // baseline mining logic.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // this pattern is EXPANDED (recursed into) — counted BEFORE node-prune TSU
        // FAIR COUNTING (generation counts): candidateCount MOVED out of the entry. I/S-ext
        // candidates are counted WHEN GENERATED (before TSU filtering) below — consistent
        // with the proposed algorithm.

        double exactUtil = 0, tsuAccumulated = 0;
        for (Tracker t : node.trackerList.trackers.values()) {
            exactUtil += t.exactUtility;
            tsuAccumulated += t.tsu;
        }
        node.exactUtilityD = exactUtil;
        node.tsuD = tsuAccumulated;

        // Safety net: tsu must already have passed when the node was added to the CP-tree.
        // Keep the check here to protect the root + 1-item entry.
        if (node.tsuD < currentMinUtil) {
            prunedTSU++;
            return;
        }

        Map<Integer, TrackerList> iExtensions = new HashMap<>();
        Map<Integer, TrackerList> sExtensions = new HashMap<>();

        for (Tracker t : node.trackerList.trackers.values()) {
            Sequence seq = globalDB.get(t.seqId);
            for (Instance inst : t.instances) {
                QItemset currentSet = seq.itemsets.get(inst.itemsetIdx);
                for (int i = inst.itemIdx + 1; i < currentSet.items.size(); i++) {
                    QItem item = currentSet.items.get(i);
                    TrackerList tl = iExtensions.computeIfAbsent(item.id, k -> new TrackerList());
                    tl.getTracker(seq.id).addInstance(new Instance(inst.itemsetIdx, i, inst.utility + item.utility), seq);
                }
                for (int is = inst.itemsetIdx + 1; is < seq.itemsets.size(); is++) {
                    QItemset nextSet = seq.itemsets.get(is);
                    for (int ii = 0; ii < nextSet.items.size(); ii++) {
                        QItem item = nextSet.items.get(ii);
                        TrackerList tl = sExtensions.computeIfAbsent(item.id, k -> new TrackerList());
                        tl.getTracker(seq.id).addInstance(new Instance(is, ii, inst.utility + item.utility), seq);
                    }
                }
            }
        }

        // FAIR COUNTING (generation counts): EVERY I/S-ext candidate generated, BEFORE filtering by TSU.
        candidateCount += iExtensions.size() + sExtensions.size();

        // Finalize TSU for I-Ext, check TSU BEFORE storing into the CP-tree.
        // Per the IncUSP-Miner+ 2018 paper: the CP-tree stores ONLY candidates with TSU >= min_util.
        // Previously the code stored TSU-pruned children too -> the CP-tree bloated and caused OOM.
        Iterator<Map.Entry<Integer, TrackerList>> itI = iExtensions.entrySet().iterator();
        while (itI.hasNext()) {
            Map.Entry<Integer, TrackerList> entry = itI.next();
            TrackerList childTl = entry.getValue();
            double childAggTsu = 0;
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
                childAggTsu += childT.tsu;
            }
            if (childAggTsu < currentMinUtil) {
                prunedTSU++;
                itI.remove(); // Do NOT store in the CP-tree — TrackerList is GC'd immediately
                continue;
            }
            Pattern np = node.pattern.iConcatenate(entry.getKey());
            CPTreeNode child = new CPTreeNode(np);
            child.trackerList = childTl;
            node.iChildren.put(entry.getKey(), child);
            expandNode(child, db, depth + 1);
            itI.remove();
        }

        Iterator<Map.Entry<Integer, TrackerList>> itS = sExtensions.entrySet().iterator();
        while (itS.hasNext()) {
            Map.Entry<Integer, TrackerList> entry = itS.next();
            TrackerList childTl = entry.getValue();
            double childAggTsu = 0;
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
                childAggTsu += childT.tsu;
            }
            if (childAggTsu < currentMinUtil) {
                prunedTSU++;
                itS.remove();
                continue;
            }
            Pattern np = node.pattern.sConcatenate(entry.getKey());
            CPTreeNode child = new CPTreeNode(np);
            child.trackerList = childTl;
            node.sChildren.put(entry.getKey(), child);
            expandNode(child, db, depth + 1);
            itS.remove();
        }
    }

    private void updateAndExpandNode(CPTreeNode node, TrackerList deltaTrackers, List<Sequence> deltaD) {
        exploredNodes++; // this pattern is EXPANDED in the incremental phase — counted BEFORE node-prune TSU

        // Merge Trackers from deltaTrackers into the current node
        if (deltaTrackers != null) {
            for (Map.Entry<Integer, Tracker> entry : deltaTrackers.trackers.entrySet()) {
                node.trackerList.trackers.put(entry.getKey(), entry.getValue());
            }
        }

        double newExactUtil = 0, newTsu = 0;
        for (Tracker t : node.trackerList.trackers.values()) {
            newExactUtil += t.exactUtility;
            newTsu += t.tsu;
        }
        node.exactUtilityD = newExactUtil;
        node.tsuD = newTsu;

        if (node.tsuD < currentMinUtil) {
            prunedTSU++;
            return;
        }

        Map<Integer, TrackerList> deltaIExts = new HashMap<>();
        Map<Integer, TrackerList> deltaSExts = new HashMap<>();

        if (deltaTrackers != null) {
            for (Tracker t : deltaTrackers.trackers.values()) {
                Sequence seq = globalDB.get(t.seqId);
                for (Instance inst : t.instances) {
                    QItemset currentSet = seq.itemsets.get(inst.itemsetIdx);
                    for (int i = inst.itemIdx + 1; i < currentSet.items.size(); i++) {
                        QItem item = currentSet.items.get(i);
                        TrackerList tl = deltaIExts.computeIfAbsent(item.id, k -> new TrackerList());
                        tl.getTracker(seq.id).addInstance(new Instance(inst.itemsetIdx, i, inst.utility + item.utility), seq);
                    }
                    for (int is = inst.itemsetIdx + 1; is < seq.itemsets.size(); is++) {
                        QItemset nextSet = seq.itemsets.get(is);
                        for (int ii = 0; ii < nextSet.items.size(); ii++) {
                            QItem item = nextSet.items.get(ii);
                            TrackerList tl = deltaSExts.computeIfAbsent(item.id, k -> new TrackerList());
                            tl.getTracker(seq.id).addInstance(new Instance(is, ii, inst.utility + item.utility), seq);
                        }
                    }
                }
            }
        }

        // FAIR COUNTING (generation counts): EVERY I/S-ext candidate in the incremental batch generated, BEFORE finalizing/filtering TSU.
        candidateCount += deltaIExts.size() + deltaSExts.size();

        // Finalize TSU for the incremental batch's extensions
        for (Map.Entry<Integer, TrackerList> entry : deltaIExts.entrySet()) {
            TrackerList childTl = entry.getValue();
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
            }
        }

        for (Map.Entry<Integer, TrackerList> entry : deltaSExts.entrySet()) {
            TrackerList childTl = entry.getValue();
            for (Tracker childT : childTl.trackers.values()) {
                Tracker parentT = node.trackerList.trackers.get(childT.seqId);
                childT.finalizeTSU(parentT, globalDB.get(childT.seqId));
            }
        }

        Set<Integer> allIKeys = new HashSet<>(node.iChildren.keySet());
        allIKeys.addAll(deltaIExts.keySet());
        for (int key : allIKeys) {
            CPTreeNode child = node.iChildren.get(key);
            if (child == null) {
                Pattern np = node.pattern.iConcatenate(key);
                child = new CPTreeNode(np);
                node.iChildren.put(key, child);
            }
            updateAndExpandNode(child, deltaIExts.get(key), deltaD);
        }

        Set<Integer> allSKeys = new HashSet<>(node.sChildren.keySet());
        allSKeys.addAll(deltaSExts.keySet());
        for (int key : allSKeys) {
            CPTreeNode child = node.sChildren.get(key);
            if (child == null) {
                Pattern np = node.pattern.sConcatenate(key);
                child = new CPTreeNode(np);
                node.sChildren.put(key, child);
            }
            updateAndExpandNode(child, deltaSExts.get(key), deltaD);
        }
    }

    private void countHUSP(CPTreeNode node) {
        if (node == null) return;
        if (node != root && node.exactUtilityD >= currentMinUtil) {
            largeMap.put(node.pattern, node.exactUtilityD);
        }
        for (CPTreeNode child : node.iChildren.values()) countHUSP(child);
        for (CPTreeNode child : node.sChildren.values()) countHUSP(child);
        patternCount = largeMap.size();
    }

    public void exportLargePatterns(String outputPath) {
        if (outputPath == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            List<Map.Entry<Pattern, Double>> sortedEntries = new ArrayList<>(largeMap.entrySet());
            sortedEntries.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));
            for (Map.Entry<Pattern, Double> entry : sortedEntries) {
                writer.write(entry.getKey().toIntuitiveString() + " #UTIL: " + entry.getValue());
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing IncUSP-MinerPlus file: " + e.getMessage());
        }
    }
}