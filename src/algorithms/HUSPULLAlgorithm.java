package algorithms;

import common.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Reference baseline: HUSP-ULL (IEEE Transactions on Cybernetics, 2021)
 * Instrumented for EXPERIMENTAL MEASUREMENT:
 * - Records the total number of candidates generated.
 * - Records the number of prunes by SWU (for 1-item).
 * - Records the number of prunes by LAR (Look-Ahead Removing - subtree level).
 * - Records the number of prunes by IIP/PEU (Irrelevant Item Pruning - item level).
 */
public class HUSPULLAlgorithm {

    // ========================================================================
    // 1. UL-NODE STRUCTURE FOR TRACKING
    // ========================================================================
    static class ULNode {
        int seqId;
        int itemsetIdx;
        int itemIdx;
        double prefixUtility;
        double remainingUtility;

        public ULNode(int seqId, int itemsetIdx, int itemIdx, double prefixUtility, double remainingUtility) {
            this.seqId = seqId;
            this.itemsetIdx = itemsetIdx;
            this.itemIdx = itemIdx;
            this.prefixUtility = prefixUtility;
            this.remainingUtility = remainingUtility;
        }
    }

    // ========================================================================
    // 2. EXPERIMENTAL METRIC VARIABLES (Metrics)
    // ========================================================================
    public double minUtility;
    public int patternCount = 0;

    // Counters for logging
    public long candidateCount = 0;
    public long exploredNodes = 0; // Patterns ACTUALLY expanded (recursed into) — measures pruning effectiveness
    public long prunedSWU = 0;  // Number of 1-items pruned by SWU
    public long prunedLAR = 0;  // Branch pruning (subtree-level)
    public long prunedIIP = 0;  // Candidate pruning (item-level PEU)

    public Map<Pattern, Double> largeMap = new HashMap<>();
    private Map<Integer, Sequence> seqLookupMap = new HashMap<>();

    public HUSPULLAlgorithm(double absoluteMinUtility) {
        this.minUtility = absoluteMinUtility;
    }

    // ========================================================================
    // 3. HUSP-ULL ALGORITHM CORE
    // ========================================================================
    public void run(List<Sequence> database) {
        // Reset all metrics before running a new batch
        largeMap.clear();
        patternCount = 0;
        candidateCount = 0;
        exploredNodes = 0;
        prunedSWU = 0;
        prunedLAR = 0;
        prunedIIP = 0;
        seqLookupMap.clear();

        // 1. Precompute the suffix sum (O(1) remaining-utility matrix)
        for (Sequence seq : database) {
            seq.calculateRemainingUtilities();
            seqLookupMap.put(seq.id, seq);
        }

        // 2. Compute SWU (Sequence-Weighted Utility) for length-1 items
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

        // FAIR COUNTING (generation counts): EVERY 1-item generated, BEFORE filtering by SWU.
        candidateCount += initialSWU.size();

        // 3. Filter promising items by SWU
        List<Integer> promisingItems = new ArrayList<>();
        for (Map.Entry<Integer, Double> entry : initialSWU.entrySet()) {
            if (entry.getValue() >= minUtility) {
                promisingItems.add(entry.getKey());
            } else {
                prunedSWU++; // Pruned by SWU
            }
        }
        Collections.sort(promisingItems); // Lexicographical order

        // 4. Start the recursion (Pattern-Growth)
        for (Integer item : promisingItems) {
            Pattern pattern = new Pattern(item);
            List<ULNode> initialULList = buildInitialULList(database, item);

            double maxUtil = calculateMaxUtility(initialULList);
            if (maxUtil >= minUtility) {
                savePattern(pattern, maxUtil);
            }

            huspULLRecursive(pattern, initialULList, 1);
        }
    }

    private void savePattern(Pattern pattern, double utility) {
        largeMap.put(pattern, utility);
        patternCount = largeMap.size();
    }

    private void huspULLRecursive(Pattern prefix, List<ULNode> ulList, int depth) {
        // DEFENSE LAYER 2: prevent StackOverflow on extremely long sequence data.
        // 2000 is a safety net (actual depth <= ~270) -> does not change results.
        if (depth > 2000) return;
        // MEASUREMENT DEFENSE: stop IMMEDIATELY when the runner cancels (timeout) -> avoid an
        // orphan thread continuing to consume CPU / hold heap and CONTAMINATING the next run's
        // time/memory. Only triggers once timed out (result discarded) -> does NOT change the
        // baseline mining logic.
        if (Thread.currentThread().isInterrupted()) return;
        exploredNodes++; // this pattern is EXPANDED (recursed into) — counted BEFORE LAR-prune
        // FAIR COUNTING (generation counts): candidateCount MOVED out of the entry. I/S-ext
        // candidates are counted WHEN GENERATED (before PEU/IIP filtering) below — consistent
        // with the proposed algorithm.
        // ==========================================================
        // TIER-2 PRUNING (SUBTREE-LEVEL): LOOK-AHEAD REMOVING (LAR)
        // ==========================================================
        double totalMaxUpperBound = 0;
        Map<Integer, Double> seqMaxUBMap = new HashMap<>();
        for (ULNode node : ulList) {
            double currentUB = node.prefixUtility + node.remainingUtility;
            if (currentUB > seqMaxUBMap.getOrDefault(node.seqId, 0.0)) {
                seqMaxUBMap.put(node.seqId, currentUB);
            }
        }
        for (double ub : seqMaxUBMap.values()) totalMaxUpperBound += ub;

        // If the total upper bound does not reach the threshold, cut this entire branch
        if (totalMaxUpperBound < minUtility) {
            prunedLAR++; // Record one successful branch prune
            return;
        }

        // ==========================================================
        // CANDIDATE GENERATION
        // ==========================================================
        Map<Integer, List<ULNode>> iCandidatesMap = new HashMap<>();
        Map<Integer, List<ULNode>> sCandidatesMap = new HashMap<>();

        for (ULNode node : ulList) {
            Sequence originalSeq = seqLookupMap.get(node.seqId);
            if (originalSeq == null) continue;

            // 1. I-Extension
            QItemset currentSet = originalSeq.itemsets.get(node.itemsetIdx);
            for (int j = node.itemIdx + 1; j < currentSet.items.size(); j++) {
                QItem targetItem = currentSet.items.get(j);
                double newPrefixUtil = node.prefixUtility + targetItem.utility;
                ULNode newNode = new ULNode(node.seqId, node.itemsetIdx, j, newPrefixUtil, originalSeq.getRestUtility(node.itemsetIdx, j));
                iCandidatesMap.computeIfAbsent(targetItem.id, k -> new ArrayList<>()).add(newNode);
            }

            // 2. S-Extension
            for (int i = node.itemsetIdx + 1; i < originalSeq.itemsets.size(); i++) {
                QItemset targetSet = originalSeq.itemsets.get(i);
                for (int j = 0; j < targetSet.items.size(); j++) {
                    QItem targetItem = targetSet.items.get(j);
                    double newPrefixUtil = node.prefixUtility + targetItem.utility;
                    ULNode newNode = new ULNode(node.seqId, i, j, newPrefixUtil, originalSeq.getRestUtility(i, j));
                    sCandidatesMap.computeIfAbsent(targetItem.id, k -> new ArrayList<>()).add(newNode);
                }
            }
        }

        // FAIR COUNTING (generation counts): EVERY I/S-ext candidate (grouped by item) generated,
        // BEFORE filtering by PEU/IIP — consistent with the proposed algorithm.
        candidateCount += iCandidatesMap.size() + sCandidatesMap.size();

        // ==========================================================
        // TIER-1 PRUNING (ITEM-LEVEL): IRRELEVANT ITEM PRUNING (IIP / PEU)
        // Memory hygiene: use an Iterator to free each entry as soon as it is processed,
        // avoiding keeping the whole I/S candidate map on the call stack throughout the recursion.
        // ==========================================================

        // Process the I-Extension branch
        Iterator<Map.Entry<Integer, List<ULNode>>> itI = iCandidatesMap.entrySet().iterator();
        while (itI.hasNext()) {
            Map.Entry<Integer, List<ULNode>> entry = itI.next();
            List<ULNode> nodes = entry.getValue();

            if (calculatePEU(nodes) >= minUtility) { // Passes the PEU check
                Pattern newPattern = prefix.iConcatenate(entry.getKey());
                double actualUtility = calculateMaxUtility(nodes);
                if (actualUtility >= minUtility) {
                    savePattern(newPattern, actualUtility);
                }
                huspULLRecursive(newPattern, nodes, depth + 1);
            } else {
                prunedIIP++; // Pruned by IIP/PEU
            }
            itI.remove(); // Free the nodes reference immediately after processing
        }

        // Process the S-Extension branch
        Iterator<Map.Entry<Integer, List<ULNode>>> itS = sCandidatesMap.entrySet().iterator();
        while (itS.hasNext()) {
            Map.Entry<Integer, List<ULNode>> entry = itS.next();
            List<ULNode> nodes = entry.getValue();

            if (calculatePEU(nodes) >= minUtility) {
                Pattern newPattern = prefix.sConcatenate(entry.getKey());
                double actualUtility = calculateMaxUtility(nodes);
                if (actualUtility >= minUtility) {
                    savePattern(newPattern, actualUtility);
                }
                huspULLRecursive(newPattern, nodes, depth + 1);
            } else {
                prunedIIP++;
            }
            itS.remove();
        }
    }

    private double calculatePEU(List<ULNode> nodes) {
        Map<Integer, Double> maxUBPerSeq = new HashMap<>();
        for (ULNode node : nodes) {
            double ub = node.prefixUtility + node.remainingUtility;
            if (ub > maxUBPerSeq.getOrDefault(node.seqId, 0.0)) {
                maxUBPerSeq.put(node.seqId, ub);
            }
        }
        double totalPEU = 0;
        for (double val : maxUBPerSeq.values()) totalPEU += val;
        return totalPEU;
    }

    private double calculateMaxUtility(List<ULNode> nodes) {
        Map<Integer, Double> maxUtilPerSeq = new HashMap<>();
        for (ULNode node : nodes) {
            if (node.prefixUtility > maxUtilPerSeq.getOrDefault(node.seqId, 0.0)) {
                maxUtilPerSeq.put(node.seqId, node.prefixUtility);
            }
        }
        double total = 0;
        for (double val : maxUtilPerSeq.values()) total += val;
        return total;
    }

    private List<ULNode> buildInitialULList(List<Sequence> db, int targetItem) {
        List<ULNode> list = new ArrayList<>();
        for (Sequence seq : db) {
            for (int i = 0; i < seq.itemsets.size(); i++) {
                QItemset is = seq.itemsets.get(i);
                for (int j = 0; j < is.items.size(); j++) {
                    if (is.items.get(j).id == targetItem) {
                        list.add(new ULNode(seq.id, i, j, is.items.get(j).utility, seq.getRestUtility(i, j)));
                    }
                }
            }
        }
        return list;
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
            System.err.println("Error writing HUSP-ULL file: " + e.getMessage());
        }
    }
}