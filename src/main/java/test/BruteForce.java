package test;

import java.util.ArrayList;
import java.util.List;

/**
 * Definition-level reference for one pattern, written independently of every miner: it shares no
 * matching, dominance or pattern-parsing code with them.
 *
 * <p>The utility of a pattern in a sequence is the maximum, over every embedding (strictly increasing
 * event indices, each pattern itemset contained in its event), of the summed item utilities; the
 * database utility is the sum over sequences. The maximum period uses virtual boundaries 0 and N+1
 * over 1-based sequence numbers. Embeddings are enumerated exhaustively, so this is only for tiny
 * databases.
 */
public final class BruteForce {

    private BruteForce() {}

    /** Pattern as a list of itemsets, each a sorted int array, e.g. {@code <(1 2)(3)>}. */
    public static List<int[]> parse(String key) {
        List<int[]> out = new ArrayList<>();
        String body = key.trim();
        if (body.startsWith("<")) body = body.substring(1);
        if (body.endsWith(">")) body = body.substring(0, body.length() - 1);
        int i = 0;
        while (i < body.length()) {
            int open = body.indexOf('(', i);
            if (open < 0) break;
            int close = body.indexOf(')', open);
            String[] toks = body.substring(open + 1, close).trim().split("[,\\s]+");
            int[] set = new int[toks.length];
            for (int t = 0; t < toks.length; t++) set[t] = Integer.parseInt(toks[t]);
            java.util.Arrays.sort(set);
            out.add(set);
            i = close + 1;
        }
        return out;
    }

    /** {utility, maxPeriod} of {@code pattern} in {@code db}; utility 0 and period N+1 if absent. */
    public static long[] evaluate(List<int[]> pattern, List<List<int[]>> db) {
        long total = 0;
        int prev = 0, maxPer = 0;
        for (int s = 0; s < db.size(); s++) {
            long best = bestEmbedding(pattern, 0, db.get(s), 0);
            if (best < 0) continue;
            total += best;
            maxPer = Math.max(maxPer, (s + 1) - prev);
            prev = s + 1;
        }
        maxPer = Math.max(maxPer, db.size() + 1 - prev);
        return new long[]{total, maxPer};
    }

    /** Best utility of pattern[k..] embedded at events from index e on; -1 if impossible. */
    private static long bestEmbedding(List<int[]> pattern, int k, List<int[]> seq, int e) {
        if (k == pattern.size()) return 0;
        long best = -1;
        for (int ev = e; ev < seq.size(); ev++) {
            long u = itemsetUtility(pattern.get(k), seq.get(ev));
            if (u < 0) continue;
            long rest = bestEmbedding(pattern, k + 1, seq, ev + 1);
            if (rest >= 0) best = Math.max(best, u + rest);
        }
        return best;
    }

    /** Summed utility of {@code set} in event {@code ev} ([item, util, item, util, ...]); -1 if not contained. */
    private static long itemsetUtility(int[] set, int[] ev) {
        long u = 0;
        for (int item : set) {
            long found = -1;
            for (int j = 0; j < ev.length; j += 2) if (ev[j] == item) { found = ev[j + 1]; break; }
            if (found < 0) return -1;
            u += found;
        }
        return u;
    }
}
