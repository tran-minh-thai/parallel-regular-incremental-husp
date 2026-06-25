package common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Sequence {
    public int id;
    public List<QItemset> itemsets = new ArrayList<>();
    public double sequenceUtility = 0;

    // Matrix storing remaining (rest) utility for O(1) lookup
    public List<List<Double>> remainingUtilities = new ArrayList<>();

    // ====================================================================
    //  FLATTENED REPRESENTATION (for Pre-HUSPM-Adaptive)
    //
    //  Flatten the entire List<QItemset> -> List<List<QItem>> into 1D
    //  primitive arrays. Goal: cache-friendly + eliminate Object/iterator
    //  overhead in the recursive hot path. Other algorithms (USpan,
    //  HUSP-ULL, ...) still use the old itemsets/remainingUtilities; only
    //  Adaptive reads the flat fields.
    //
    //  Layout (4 primitive arrays — minimizes memory footprint):
    //    int[]    flatItems[k]            = item.id at flat position k
    //    double[] flatUtilities[k]        = utility at flat position k
    //    boolean[] isItemsetEnd[k]        = true if k is the LAST position of
    //                                       its itemset (next itemset starts at k+1)
    //    double[] flatRemainingUtility[k] = suffix sum utility from k+1 to end
    //    flatLen                          = total number of items (flat array size)
    //
    //  isItemsetEnd replaces the old flatToItemsetIdx + itemsetStart (~5x smaller):
    //    - flatToItemsetIdx: 4L bytes — not needed (boundary folded into scan)
    //    - itemsetStart:     4(M+1) bytes — not needed (boundary known via isItemsetEnd)
    //    - isItemsetEnd:     L bytes (boolean[], 1 byte per entry in the JVM)
    //
    //  Usage (Pass-2 scan):
    //    for (int q = parentFlatIdx + 1; q < flatLen; q++) {
    //        if (flatItems[q] == itemToFind) { ... I-ext match ... }
    //        if (isItemsetEnd[q]) break;   // crossed itemset boundary -> I-ext done
    //    }
    //    // S-ext starts at q+1 (after breaking at isItemsetEnd[q])
    // ====================================================================
    public int[] flatItems;
    public double[] flatUtilities;
    public boolean[] isItemsetEnd;
    public double[] flatRemainingUtility;
    public int flatLen = 0;

    public Sequence(int id) {
        this.id = id;
    }

    // Precompute the remaining utility (suffix sum) from right to left
    public void calculateRemainingUtilities() {
        remainingUtilities.clear();
        double currentRemaining = 0;
        for (int i = itemsets.size() - 1; i >= 0; i--) {
            QItemset itemset = itemsets.get(i);
            List<Double> itemsetRemUtil = new ArrayList<>(Collections.nCopies(itemset.items.size(), 0.0));
            for (int j = itemset.items.size() - 1; j >= 0; j--) {
                itemsetRemUtil.set(j, currentRemaining);
                currentRemaining += itemset.items.get(j).utility;
            }
            remainingUtilities.add(0, itemsetRemUtil);
        }
        this.sequenceUtility = currentRemaining;
    }

    /**
     * Build the flat primitive representation. Idempotent: calling again rebuilds.
     * The Adaptive algorithm calls this once per sequence when loading into dbLookup.
     */
    public void buildFlatRepresentation() {
        int total = 0;
        for (QItemset is : itemsets) total += is.items.size();
        flatLen = total;
        flatItems = new int[total];
        flatUtilities = new double[total];
        isItemsetEnd = new boolean[total];
        flatRemainingUtility = new double[total];

        int pos = 0;
        for (int i = 0; i < itemsets.size(); i++) {
            QItemset is = itemsets.get(i);
            int n = is.items.size();
            for (int j = 0; j < n; j++) {
                QItem qi = is.items.get(j);
                flatItems[pos] = qi.id;
                flatUtilities[pos] = qi.utility;
                // Mark the last element of the itemset
                if (j == n - 1) isItemsetEnd[pos] = true;
                pos++;
            }
        }

        // Build remaining utility (suffix sum of utilities after position k).
        double running = 0;
        for (int k = total - 1; k >= 0; k--) {
            flatRemainingUtility[k] = running;
            running += flatUtilities[k];
        }
        if (this.sequenceUtility == 0) this.sequenceUtility = running;
    }

    /** O(1) rest utility lookup via flat index. */
    public double getRestUtilityFlat(int flatIdx) {
        return flatRemainingUtility[flatIdx];
    }

    // Get remaining utility in O(1), or compute manually in O(N) if not initialized
    public double getRestUtility(int itemsetIdx, int itemIdx) {
        if (!remainingUtilities.isEmpty()) {
            return remainingUtilities.get(itemsetIdx).get(itemIdx);
        }

        // O(N) fallback for algorithms that do not call calculateRemainingUtilities()
        double rest = 0;
        for (int i = itemsetIdx; i < itemsets.size(); i++) {
            QItemset is = itemsets.get(i);
            int start = (i == itemsetIdx) ? (itemIdx + 1) : 0;
            for (int j = start; j < is.items.size(); j++) {
                rest += is.items.get(j).utility;
            }
        }
        return rest;
    }
}