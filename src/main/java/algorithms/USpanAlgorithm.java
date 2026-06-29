package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Reference baseline: USpan (Yin et al., KDD 2012)
 * Instrumented for EXPERIMENTAL MEASUREMENT:
 * - Tracks candidateCount precisely at the point extension candidates are generated.
 * - Tracks prunedSWU (number of candidates pruned by the local and global SWU bound).
 * - Integrates Width Pruning (SWU) and Depth Pruning (O(1) suffix sum).
 */
public class USpanAlgorithm {

    // ========================================================================
    // 1. STATE-TRACKING STRUCTURE (PROJECTED DATABASE)
    // ========================================================================

    static class Instance {
        int itemsetIdx;
        int itemIdx;
        double utility;

        public Instance(int itemsetIdx, int itemIdx, double utility) {
            this.itemsetIdx = itemsetIdx;
            this.itemIdx = itemIdx;
            this.utility = utility;
        }
    }

    static class ProjectedSequence {
        Sequence seq;
        List<Instance> instances;

        public ProjectedSequence(Sequence seq, List<Instance> instances) {
            this.seq = seq;
            this.instances = instances;
        }
    }

    // ========================================================================
    // 2. EXPERIMENTAL METRIC VARIABLES (Metrics)
    // ========================================================================

    public double minUtility;
    public int patternCount = 0;

    // Counters for logging per the design specification
    public long candidateCount = 0;
    public long exploredNodes = 0; // Patterns ACTUALLY expanded (recursed into) — measures pruning effectiveness
    public long prunedSWU = 0; // Number of prunes by Sequence-Weighted Utilization
    public long prunedDepth = 0; // Number of branches cut by Depth Pruning (optional tracking)

    // Storage for qualifying HUSP patterns (for file export)
    public Map<Pattern, Double> largeMap = new HashMap<>();

    // Counts index-mismatch warnings (Instance vs Sequence desync). Cap the log so it does
    // not flood stderr / slow the experiment runner on structurally malformed datasets.
    private long boundaryWarnings = 0;
    private static final long MAX_BOUNDARY_WARN = 20;

    public USpanAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    /**
     * Boundary check: an Instance's indices (itemsetIdx, itemIdx) must lie within the
     * actual structure of seq. If out of range -> log a warning (capped) + return false
     * so the caller SKIPS that instance instead of throwing IndexOutOfBoundsException and
     * crashing the runner. Does NOT change the Width/Depth pruning mechanism — only blocks
     * out-of-bounds array access.
     */
    private boolean inBounds(Sequence seq, int itemsetIdx, int itemIdx) {
        if (itemsetIdx < 0 || itemsetIdx >= seq.itemsets.size()) {
            if (boundaryWarnings++ < MAX_BOUNDARY_WARN) {
                System.err.println("[USpan][WARN] itemsetIdx=" + itemsetIdx + " out of bounds (itemsets="
                        + seq.itemsets.size() + ", seqId=" + seq.id + ") — skipping instance");
            }
            return false;
        }
        if (itemIdx < 0 || itemIdx >= seq.itemsets.get(itemsetIdx).items.size()) {
            if (boundaryWarnings++ < MAX_BOUNDARY_WARN) {
                System.err.println("[USpan][WARN] itemIdx=" + itemIdx + " out of bounds (items="
                        + seq.itemsets.get(itemsetIdx).items.size() + ", seqId=" + seq.id + ") — skipping instance");
            }
            return false;
        }
        return true;
    }

    // ========================================================================
    // 3. USPAN ALGORITHM CORE
    // ========================================================================

    public void run(List<Sequence> database) {
        // Optimization: recompute the O(1) suffix sum only if the database has not been computed
        for (Sequence seq : database) {
            if (seq.remainingUtilities.isEmpty()) {
                seq.calculateRemainingUtilities();
            }
        }

        // Reset metrics
        largeMap.clear();
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedSWU = 0;
        prunedDepth = 0;

        // ==============================================================
        // STEP 1: WIDTH PRUNING (at the 1-item generation)
        // ==============================================================
        Map<Integer, Double> initialSWU = new HashMap<>();
        for (Sequence seq : database) {
            Set<Integer> uniqueItems = new HashSet<>();
            for (QItemset is : seq.itemsets) {
                for (QItem item : is.items) uniqueItems.add(item.id);
            }
            for (Integer id : uniqueItems) {
                initialSWU.put(id, initialSWU.getOrDefault(id, 0.0) + seq.sequenceUtility);
            }
        }

        // FAIR COUNTING (generation counts): EVERY 1-item generated, BEFORE filtering by the global SWU.
        candidateCount += initialSWU.size();

        List<Integer> promisingItems = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : initialSWU.entrySet()) {
            if (entry.getValue() >= minUtility) {
                promisingItems.add(entry.getKey());
            } else {
                prunedSWU++; // Pruned by the global SWU
            }
        }
        Collections.sort(promisingItems); // Lexicographic order

        // ==============================================================
        // STEP 2: PROJECTION AND RECURSION (PATTERN-GROWTH)
        // ==============================================================
        for (Integer item : promisingItems) {
            Pattern pattern = new Pattern(item);
            List<ProjectedSequence> projectedDB = buildInitialProjectedDB(database, item);

            double maxUtil = calculateMaxUtility(projectedDB);
            if (maxUtil >= minUtility) {
                savePattern(pattern, maxUtil);
            }

            uSpanRecursive(pattern, projectedDB, 1);
        }

        // Clean up initialization data
        initialSWU.clear();
        promisingItems.clear();
    }

    private void savePattern(Pattern pattern, double utility) {
        largeMap.put(pattern, utility);
        patternCount = largeMap.size();
    }

    private void uSpanRecursive(Pattern prefix, List<ProjectedSequence> projectedDB, int depth) {
        // DEFENSE LAYER 2: prevent StackOverflow on extremely long sequence data.
        // 2000 is a safety net (actual dataset depth <= ~270) -> does not change results.
        if (depth > 2000) return;
        // MEASUREMENT DEFENSE: stop IMMEDIATELY when the runner cancels (timeout) -> avoid an
        // orphan thread continuing to consume CPU / hold heap and CONTAMINATING the next run's
        // time/memory. Only triggers once timed out (result discarded) -> does NOT change the
        // baseline mining logic.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // this pattern is EXPANDED (recursed into) — counted BEFORE depth-prune (= true expansion work)
        // FAIR COUNTING (generation counts): candidateCount MOVED out of the entry. Extension
        // candidates are counted WHEN GENERATED (before local SWU filtering) below — consistent
        // with the proposed algorithm.
        // ==============================================================
        // STRATEGY 1: DEPTH PRUNING (BASED ON U_REST)
        // ==============================================================
        double maxPossibleUtility = 0;
        for (ProjectedSequence pSeq : projectedDB) {
            double seqMaxUB = 0;
            for (Instance inst : pSeq.instances) {
                // Boundary check before getRestUtility (remainingUtilities.get(itemsetIdx).get(itemIdx))
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                // Use the O(1) getRestUtility from common.Sequence
                double ub = inst.utility + pSeq.seq.getRestUtility(inst.itemsetIdx, inst.itemIdx);
                if (ub > seqMaxUB) seqMaxUB = ub;
            }
            maxPossibleUtility += seqMaxUB;
        }

        // Kill the entire DFS branch if the total potential upper bound does not reach the threshold
        if (maxPossibleUtility < minUtility) {
            prunedDepth++;
            return;
        }

        // ==============================================================
        // STRATEGY 2: WIDTH PRUNING ON PROJECTED DB (LOCAL SWU)
        // ==============================================================
        Map<Integer, Double> iListSWU = new HashMap<>();
        Map<Integer, Double> sListSWU = new HashMap<>();

        for (ProjectedSequence pSeq : projectedDB) {
            double sequenceUtilities = pSeq.seq.sequenceUtility;
            Set<Integer> iSeen = new HashSet<>();
            Set<Integer> sSeen = new HashSet<>();

            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                QItemset currentItemset = pSeq.seq.itemsets.get(inst.itemsetIdx);

                // Collect I-Extension candidates
                for (int i = inst.itemIdx + 1; i < currentItemset.items.size(); i++) {
                    iSeen.add(currentItemset.items.get(i).id);
                }
                // Collect S-Extension candidates
                for (int i = inst.itemsetIdx + 1; i < pSeq.seq.itemsets.size(); i++) {
                    for (QItem item : pSeq.seq.itemsets.get(i).items) {
                        sSeen.add(item.id);
                    }
                }
            }

            for (int item : iSeen) iListSWU.put(item, iListSWU.getOrDefault(item, 0.0) + sequenceUtilities);
            for (int item : sSeen) sListSWU.put(item, sListSWU.getOrDefault(item, 0.0) + sequenceUtilities);
        }

        // FAIR COUNTING (generation counts): EVERY I/S-ext candidate generated, BEFORE filtering by the local SWU.
        candidateCount += iListSWU.size() + sListSWU.size();

        // Count candidates and apply the local SWU
        List<Integer> iList = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : iListSWU.entrySet()) {
            if (e.getValue() >= minUtility) {
                iList.add(e.getKey());
            } else {
                prunedSWU++; // Pruned by the local SWU
            }
        }
        Collections.sort(iList);

        List<Integer> sList = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : sListSWU.entrySet()) {
            if (e.getValue() >= minUtility) {
                sList.add(e.getKey());
            } else {
                prunedSWU++; // Pruned by the local SWU
            }
        }
        Collections.sort(sList);

        // ==============================================================
        // PERFORM EXTENSIONS (I-EXTENSION & S-EXTENSION)
        // ==============================================================
        for (Integer item : iList) {
            List<ProjectedSequence> newProjDB = buildIProjectedDB(projectedDB, item);
            if (!newProjDB.isEmpty()) {
                Pattern newPattern = prefix.iConcatenate(item);
                double actualUtility = calculateMaxUtility(newProjDB);
                if (actualUtility >= minUtility) savePattern(newPattern, actualUtility);
                uSpanRecursive(newPattern, newProjDB, depth + 1);
            }
        }

        for (Integer item : sList) {
            List<ProjectedSequence> newProjDB = buildSProjectedDB(projectedDB, item);
            if (!newProjDB.isEmpty()) {
                Pattern newPattern = prefix.sConcatenate(item);
                double actualUtility = calculateMaxUtility(newProjDB);
                if (actualUtility >= minUtility) savePattern(newPattern, actualUtility);
                uSpanRecursive(newPattern, newProjDB, depth + 1);
            }
        }

        // Memory optimization
        iListSWU.clear();
        sListSWU.clear();
    }

    private double calculateMaxUtility(List<ProjectedSequence> pdb) {
        double totalPatternUtility = 0;
        for (ProjectedSequence pSeq : pdb) {
            double seqMaxUtil = 0;
            for (Instance inst : pSeq.instances) {
                if (inst.utility > seqMaxUtil) seqMaxUtil = inst.utility;
            }
            totalPatternUtility += seqMaxUtil;
        }
        return totalPatternUtility;
    }

    private List<ProjectedSequence> buildInitialProjectedDB(List<Sequence> db, int targetItem) {
        List<ProjectedSequence> pdb = new ArrayList<>();
        for (Sequence seq : db) {
            List<Instance> instances = new ArrayList<>();
            for (int i = 0; i < seq.itemsets.size(); i++) {
                QItemset is = seq.itemsets.get(i);
                for (int j = 0; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        instances.add(new Instance(i, j, is.items.get(j).utility));
                    }
                }
            }
            if (!instances.isEmpty()) pdb.add(new ProjectedSequence(seq, instances));
        }
        return pdb;
    }

    private List<ProjectedSequence> buildIProjectedDB(List<ProjectedSequence> pdb, int targetItem) {
        List<ProjectedSequence> newPdb = new ArrayList<>();
        for (ProjectedSequence pSeq : pdb) {
            // 64-bit Long key (high 32 = itemsetIdx, low 32 = itemIdx) avoids collisions on long sequences
            Map<Long, Instance> bestInstances = new HashMap<>();
            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                QItemset is = pSeq.seq.itemsets.get(inst.itemsetIdx);
                for (int j = inst.itemIdx + 1; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        double newUtil = inst.utility + is.items.get(j).utility;
                        long key = ((long) inst.itemsetIdx << 32) | (j & 0xFFFFFFFFL);
                        Instance prevSeqId = bestInstances.get(key);
                        if (prevSeqId == null || prevSeqId.utility < newUtil) {
                            bestInstances.put(key, new Instance(inst.itemsetIdx, j, newUtil));
                        }
                    }
                }
            }
            if (!bestInstances.isEmpty()) {
                newPdb.add(new ProjectedSequence(pSeq.seq, new ArrayList<>(bestInstances.values())));
            }
        }
        return newPdb;
    }

    private List<ProjectedSequence> buildSProjectedDB(List<ProjectedSequence> pdb, int targetItem) {
        List<ProjectedSequence> newPdb = new ArrayList<>();
        for (ProjectedSequence pSeq : pdb) {
            Map<Long, Instance> bestInstances = new HashMap<>();
            for (Instance inst : pSeq.instances) {
                if (!inBounds(pSeq.seq, inst.itemsetIdx, inst.itemIdx)) continue;
                for (int i = inst.itemsetIdx + 1; i < pSeq.seq.itemsets.size(); i++) {
                    QItemset is = pSeq.seq.itemsets.get(i);
                    for (int j = 0; j < is.items.size(); j++) {
                        if (is.items.get(j).id == targetItem) {
                            double newUtil = inst.utility + is.items.get(j).utility;
                            long key = ((long) i << 32) | (j & 0xFFFFFFFFL);
                            Instance prevSeqId = bestInstances.get(key);
                            if (prevSeqId == null || prevSeqId.utility < newUtil) {
                                bestInstances.put(key, new Instance(i, j, newUtil));
                            }
                        }
                    }
                }
            }
            if (!bestInstances.isEmpty()) {
                newPdb.add(new ProjectedSequence(pSeq.seq, new ArrayList<>(bestInstances.values())));
            }
        }
        return newPdb;
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
            System.err.println("Error writing USpan file: " + e.getMessage());
        }
    }
}