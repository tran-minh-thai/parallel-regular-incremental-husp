package algorithms;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quantitative Sequence Database (QSDB) for the incremental framework.
 * <p>
 * The data model matches {@code preliminaryVN.tex} (Definition 1):
 * each sequence {@code S = <E_1,...,E_n>} is an ordered list of itemsets; each itemset
 * {@code E} is a flat interleaved array {@code [item, util, item, util, ...]}
 * sorted ascending by item id (for ordered same-itemset matching).
 * <p>
 * This class supports <b>tail insertion</b> ({@link #appendBatch}) — the foundational invariant
 * of incremental update: a new batch carries only SIDs greater than every existing SID, so all old
 * positions and successive differences are invariant (Lemma 3, {@code lem:tail}).
 */
public class QSeqDatabase {

    // =================================================================================
    //  CSR (Compressed Sparse Row) STORAGE — flattens the 3-level structure (sequence→itemset→item)
    //  into 1-D primitive arrays + OFFSET arrays. Cache-friendly, no objects/pointers,
    //  share-invariant across threads. Supports TAIL INSERTION by APPENDING (grow).
    //
    //  Access the j-th item of LOCAL itemset k in sequence s:
    //     int ge  = seqEventStart[s] + k;            // GLOBAL itemset index
    //     int p0  = eventItemStart[ge];              // flat position of itemset start
    //     int pos = p0 + j;                          // item position
    //     items[pos], utils[pos], rems[pos]
    //  Number of itemsets in sequence s = seqEventStart[s+1] − seqEventStart[s].
    //  Number of items in itemset ge   = eventItemStart[ge+1] − eventItemStart[ge].
    // =================================================================================
    public int[]  items = new int[1024];     // item id at each flat position
    public long[] utils = new long[1024];    // utility at each flat position
    public long[] rems  = new long[1024];    // REMAINING utility (in-sequence suffix sum) after the position
    public int[]  eventItemStart = new int[256];   // offset: global itemset → flat start position
    public int[]  seqEventStart  = new int[256];   // offset: sequence → first global itemset

    public int numPositions = 0;     // current used length of items/utils/rems
    public int numGlobalEvents = 0;  // number of global itemsets loaded
    /** Current total database utility totalDbUtility = Σ u(S). */
    public long totalDbUtility = 0;
    /** Current number of sequences. */
    public int numSequences = 0;

    public Map<Integer, Integer> externalUtility = new HashMap<>();

    // ----------------------------------------------------------------- data loading

    public void loadExternalUtility(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("[,:]");
                try {
                    externalUtility.put(Integer.parseInt(p[0].trim()),
                                        Integer.parseInt(p[1].trim()));
                } catch (Exception ignored) {}
            }
        }
    }

    /** Read the entire SPMF/QSDB file into a list of sequences (not yet loaded into db; for batching). */
    public List<List<int[]>> readSequences(String path) throws IOException {
        List<List<int[]>> all = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                List<int[]> seq = new ArrayList<>();
                for (String evStr : line.split(" -1 ")) {
                    String clean = evStr.replace("-2", "").trim();
                    if (clean.isEmpty()) continue;
                    List<Integer> tmp = new ArrayList<>();
                    for (String tok : clean.split("\\s+")) {
                        try {
                            int id, qty = 1;
                            int open = tok.indexOf('['), close = tok.indexOf(']');
                            if (open >= 0 && close > open) {           // item[qty] format
                                id = Integer.parseInt(tok.substring(0, open).trim());
                                qty = Integer.parseInt(tok.substring(open + 1, close).trim());
                            } else if (tok.contains(",")) {            // item,qty format
                                String[] q = tok.split(",");
                                id = Integer.parseInt(q[0].trim());
                                qty = Integer.parseInt(q[1].trim());
                            } else {                                   // item id only
                                id = Integer.parseInt(tok.trim());
                            }
                            int profit = externalUtility.getOrDefault(id, 1);
                            tmp.add(id);
                            tmp.add(qty * profit);
                        } catch (Exception ignored) {}
                    }
                    if (!tmp.isEmpty()) {
                        int[] ev = new int[tmp.size()];
                        for (int i = 0; i < ev.length; i++) ev[i] = tmp.get(i);
                        insertionSortByItem(ev);                       // sort by item id
                        seq.add(ev);
                    }
                }
                if (!seq.isEmpty()) all.add(seq);
            }
        }
        return all;
    }

    // ----------------------------------------------------------------- batch insertion

    /**
     * Append batch {@code batch} to the tail of the database: incrementally update {@code totalDbUtility},
     * {@code numSequences}, sequence utility and the remaining-utility array. Does NOT touch old data.
     *
     * @return array [sidStart, sidEnd) of the newly added sequences.
     */
    public int[] appendBatch(List<List<int[]>> batch) {
        int start = numSequences;
        for (List<int[]> seq : batch) {
            int seqPosStart = numPositions;
            for (int[] ev : seq) {                       // append each itemset's items to the tail of the flat array
                int m = ev.length / 2;
                ensurePosCapacity(numPositions + m);
                for (int j = 0; j < m; j++) {
                    items[numPositions] = ev[j * 2];
                    utils[numPositions] = ev[j * 2 + 1];
                    numPositions++;
                }
                numGlobalEvents++;
                ensureEventCapacity(numGlobalEvents + 1);
                eventItemStart[numGlobalEvents] = numPositions;   // sentinel offset at itemset end
            }
            // rems = IN-sequence utility suffix sum: rems[p] = Σ utils at positions after p
            long acc = 0;
            for (int p = numPositions - 1; p >= seqPosStart; p--) { rems[p] = acc; acc += utils[p]; }
            totalDbUtility += acc;                       // acc = whole-sequence utility u(S)
            numSequences++;
            ensureSeqCapacity(numSequences + 1);
            seqEventStart[numSequences] = numGlobalEvents;        // sentinel offset at sequence end
        }
        return new int[]{start, numSequences};
    }

    private void ensurePosCapacity(int need) {
        if (need <= items.length) return;
        int n = Math.max(need, items.length * 2);
        items = java.util.Arrays.copyOf(items, n);
        utils = java.util.Arrays.copyOf(utils, n);
        rems  = java.util.Arrays.copyOf(rems, n);
    }
    private void ensureEventCapacity(int need) {
        if (need > eventItemStart.length) eventItemStart = java.util.Arrays.copyOf(eventItemStart, Math.max(need, eventItemStart.length * 2));
    }
    private void ensureSeqCapacity(int need) {
        if (need > seqEventStart.length) seqEventStart = java.util.Arrays.copyOf(seqEventStart, Math.max(need, seqEventStart.length * 2));
    }

    private static void insertionSortByItem(int[] ev) {
        // sort (item,util) pairs ascending by item id
        for (int i = 2; i < ev.length; i += 2) {
            int it = ev[i], ut = ev[i + 1], j = i - 2;
            while (j >= 0 && ev[j] > it) {
                ev[j + 2] = ev[j]; ev[j + 3] = ev[j + 1];
                j -= 2;
            }
            ev[j + 2] = it; ev[j + 3] = ut;
        }
    }
}
