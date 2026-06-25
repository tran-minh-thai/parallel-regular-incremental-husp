package algorithms;

import common.Pattern;
import common.QItem;
import common.QItemset;
import common.Sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Adapter between the internal model {@code List<List<int[]>>} (itemset = interleaved
 * array [item,util,...]) and the {@link common.Sequence} model used by the reference
 * baselines (USpan, HUSP-ULL, IncUSP-Miner+). Also provides a CANONICAL pattern key for
 * matching result sets across algorithms that use different formats.
 */
public final class SeqConverter {
    private SeqConverter() {}

    /** Convert one batch {@code List<List<int[]>>} into {@code List<Sequence>}, assigning consecutive ids from {@code startId}. */
    public static List<Sequence> toSequences(List<List<int[]>> batch, int startId) {
        List<Sequence> out = new ArrayList<>(batch.size());
        int id = startId;
        for (List<int[]> seq : batch) {
            Sequence s = new Sequence(id++);
            for (int[] ev : seq) {
                QItemset is = new QItemset();
                for (int j = 0; j < ev.length; j += 2) is.items.add(new QItem(ev[j], ev[j + 1]));
                s.itemsets.add(is);
            }
            s.calculateRemainingUtilities();   // set sequenceUtility + remainingUtilities
            out.add(s);
        }
        return out;
    }

    /** Total utility of a list of sequences (totalDbUtility). */
    public static double totalUtility(List<Sequence> seqs) {
        double t = 0;
        for (Sequence s : seqs) t += s.sequenceUtility;
        return t;
    }

    /**
     * CANONICAL pattern key for cross-algorithm matching: {@code <(i i)(i)>} with the items
     * in each itemset SORTED ASCENDING, no whitespace. Applies to both patterns of form
     * {@code List<List<Integer>>} ({@link common.Pattern}) and strings {@code "<(..)(..)>"}.
     */
    public static String canonical(Pattern p) {
        StringBuilder sb = new StringBuilder("<");
        for (List<Integer> ev : p.elements) {
            sb.append('(');
            TreeSet<Integer> sorted = new TreeSet<>(ev);
            boolean first = true;
            for (int it : sorted) { if (!first) sb.append(' '); sb.append(it); first = false; }
            sb.append(')');
        }
        return sb.append('>').toString();
    }

    /** Normalize a string key {@code "<(1 2)(3)>"} into the same canonical form (sort items, drop extras). */
    public static String canonical(String key) {
        // split itemsets between ")(", take the numbers in each itemset, sort ascending
        String body = key.replace("<", "").replace(">", "").trim();
        String[] events = body.replace(")(", ")#(").split("#");
        StringBuilder sb = new StringBuilder("<");
        for (String ev : events) {
            String inner = ev.replace("(", "").replace(")", "").trim();
            TreeSet<Integer> sorted = new TreeSet<>();
            for (String tok : inner.split("[,\\s]+")) {
                if (tok.isEmpty()) continue;
                try { sorted.add(Integer.parseInt(tok)); } catch (NumberFormatException ignored) {}
            }
            sb.append('(');
            boolean first = true;
            for (int it : sorted) { if (!first) sb.append(' '); sb.append(it); first = false; }
            sb.append(')');
        }
        return sb.append('>').toString();
    }
}
