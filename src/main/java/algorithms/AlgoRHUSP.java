package algorithms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>RHusp: Regular High-Utility Sequential Pattern miner (STATIC)</h1>
 *
 * STATIC RHUSP mining engine over the whole database (non-incremental), using the PEU upper bound
 * and the regularity constraint. Corresponds to the <b>"RHusp"</b> row in the baseline table
 * (the manuscript): R/--/--, serving as the <b>full-result oracle</b> and the "re-mine from
 * scratch" reference. Condensed version of {@code codeThamKhao/AlgoRHUSPMiner.java} (RHusp,
 * Ishita2022) and {@code AlgoRHUSPMinerParallel.java} (parallel version).
 * <p>
 * The {@link #parallel} flag selects the run mode:
 * <ul>
 *   <li>{@code false}: SEQUENTIAL RHusp (original R/--/-- oracle);</li>
 *   <li>{@code true}: root-level parallel, used by {@link AlgoRHUSPRecompute} (the
 *       "proposed-static" reference re-mined from scratch each batch).</li>
 * </ul>
 * Mining uses the MAXIMUM utility measure (max over matches within each sequence), consistent
 * with the static phase of the proposed framework.
 */
public class AlgoRHUSP {

    public boolean parallel = true;

    public Map<String, long[]> mine(List<List<int[]>> db, long minUtil, int maxReg) {
        final int numSequences = db.size();
        final List<List<int[]>> ruDB = buildRemaining(db);

        // root-level candidate set (every item that appears); simple PEU filtering via processExtension
        Set<Integer> roots = new HashSet<>();
        for (List<int[]> seq : db)
            for (int[] ev : seq)
                for (int j = 0; j < ev.length; j += 2) roots.add(ev[j]);

        Map<String, long[]> results = new ConcurrentHashMap<>();

        // initial projected: one "empty" instance per sequence (eventIdx=-1)
        java.util.stream.Stream<Integer> stream =
                parallel ? roots.parallelStream() : roots.stream();
        stream.forEach(item -> {
            // TreeMap: traverse by ASCENDING seqId; REQUIRED to compute regularity (period=seqId−lastSeqId) correctly.
            Map<Integer, List<int[]>> projected = new TreeMap<>();   // seqId -> list of {eventIdx,itemIdx,curUtil}
            for (int seqId = 0; seqId < numSequences; seqId++) {
                List<int[]> l = new ArrayList<>();
                l.add(new int[]{-1, -1, 0});
                projected.put(seqId, l);
            }
            processExtension(new ArrayList<>(), projected, item, false,
                             minUtil, maxReg, db, ruDB, numSequences, results);
        });
        return results;
    }

    /** Extend the pattern by {@code item}; recurse i-ext then s-ext (see AlgoRIncHUSP_Adaptive). */
    private void processExtension(List<List<Integer>> pattern, Map<Integer, List<int[]>> projected,
                                  int item, boolean iExt, long minUtil, int maxReg,
                                  List<List<int[]>> db, List<List<int[]>> ruDB, int numSequences,
                                  Map<String, long[]> results) {
        Map<Integer, List<int[]>> next = new TreeMap<>();   // ordered by ascending seqId (correct regularity)
        long totalUtil = 0, totalPeu = 0;
        int lastSeqId = -1, maxPeriod = 0;

        for (Map.Entry<Integer, List<int[]>> en : projected.entrySet()) {
            int seqId = en.getKey();
            List<int[]> seq = db.get(seqId);
            List<int[]> remSeq = ruDB.get(seqId);
            Map<Integer, long[]> bestPerPos = new HashMap<>();  // posKey -> {curUtil}
            boolean found = false;
            // localMax = max-measure utility of the pattern in the sequence; localMaxPeu = max(util+ru), the exact PEU bound.
            long localMax = 0, localMaxPeu = 0;

            for (int[] inst : en.getValue()) {
                int instE = inst[0], instJ = inst[1]; long cur = inst[2];
                int startE = iExt ? instE : instE + 1;
                // i-extension ONLY within the same itemset (e == instE); s-extension in later itemsets.
                int endE = iExt ? Math.min(instE + 1, seq.size()) : seq.size();
                for (int e = startE; e < endE; e++) {
                    int[] ev = seq.get(e);
                    int startJ = (iExt && e == instE) ? instJ + 1 : 0;
                    for (int j = startJ; j < ev.length / 2; j++) {
                        if (ev[j * 2] == item) {
                            long nu = cur + ev[j * 2 + 1];
                            int posKey = e * 100000 + j;
                            long[] prevBest = bestPerPos.get(posKey);
                            if (prevBest == null || nu > prevBest[0]) {
                                bestPerPos.put(posKey, new long[]{nu});
                                found = true;
                                if (nu > localMax) localMax = nu;
                                long peu = nu + remSeq.get(e)[j];     // PEU bound of the match at this position
                                if (peu > localMaxPeu) localMaxPeu = peu;
                            }
                        }
                    }
                }
            }
            if (found) {
                List<int[]> list = new ArrayList<>();
                for (Map.Entry<Integer, long[]> p : bestPerPos.entrySet())
                    list.add(new int[]{p.getKey() / 100000, p.getKey() % 100000, (int) p.getValue()[0]});
                next.put(seqId, list);
                totalUtil += localMax; totalPeu += localMaxPeu;   // PEU = Σ max(util+ru) per sequence
                int period = (lastSeqId == -1) ? (seqId + 1) : (seqId - lastSeqId);
                maxPeriod = Math.max(maxPeriod, period);
                lastSeqId = seqId;
            }
        }
        if (lastSeqId == -1) return;
        maxPeriod = Math.max(maxPeriod, numSequences - lastSeqId);

        // Prune: recurse/store only when REGULAR and the PEU bound ≥ minUtil (totalPeu is the exact upper bound).
        if (maxPeriod <= maxReg && totalPeu >= minUtil) {
            List<List<Integer>> newPat = new ArrayList<>();
            for (List<Integer> ev : pattern) newPat.add(new ArrayList<>(ev));
            if (iExt) { newPat.get(newPat.size() - 1).add(item); Collections.sort(newPat.get(newPat.size() - 1)); }
            else { List<Integer> ne = new ArrayList<>(); ne.add(item); newPat.add(ne); }

            // The third field is the TRUE last occurrence. It exists because a consumer that seeds
            // incremental state from this map needs it: a placeholder there once collapsed the gap
            // spanning the seed boundary from a SUM of two components into their MAX, understating
            // periods and letting patterns past the bound -- the false positives that recall, which
            // only looks at the intersection, can never show.
            if (totalUtil >= minUtil) results.put(format(newPat), new long[]{totalUtil, maxPeriod, lastSeqId});

            for (int c : candidates(next, true,  minUtil, db, ruDB))
                processExtension(newPat, next, c, true,  minUtil, maxReg, db, ruDB, numSequences, results);
            for (int c : candidates(next, false, minUtil, db, ruDB))
                processExtension(newPat, next, c, false, minUtil, maxReg, db, ruDB, numSequences, results);
        }
    }

    /** Extension candidates by PEU bound ≥ minUtil. */
    private List<Integer> candidates(Map<Integer, List<int[]>> projected, boolean iExt,
                                     long minUtil, List<List<int[]>> db, List<List<int[]>> ruDB) {
        Map<Integer, Long> peu = new HashMap<>();
        for (Map.Entry<Integer, List<int[]>> en : projected.entrySet()) {
            int seqId = en.getKey();
            List<int[]> seq = db.get(seqId);
            List<int[]> remSeq = ruDB.get(seqId);
            long maxCur = 0;
            for (int[] inst : en.getValue()) maxCur = Math.max(maxCur, inst[2]);
            Set<Integer> seen = new HashSet<>();
            for (int[] inst : en.getValue()) {
                int startE = iExt ? inst[0] : inst[0] + 1;
                int endE = iExt ? Math.min(inst[0] + 1, seq.size()) : seq.size();
                for (int k = startE; k < endE; k++) {
                    int[] ev = seq.get(k);
                    int startJ = (iExt && k == inst[0]) ? inst[1] + 1 : 0;
                    for (int j = startJ; j < ev.length / 2; j++) {
                        int id = ev[j * 2];
                        if (seen.add(id))
                            peu.merge(id, maxCur + ev[j * 2 + 1] + remSeq.get(k)[j], Long::sum);
                    }
                }
            }
        }
        List<Integer> out = new ArrayList<>();
        peu.forEach((k, v) -> { if (v >= minUtil) out.add(k); });
        Collections.sort(out);
        return out;
    }

    static List<List<int[]>> buildRemaining(List<List<int[]>> db) {
        List<List<int[]>> ruDB = new ArrayList<>();
        for (List<int[]> seq : db) {
            int[][] tmp = new int[seq.size()][];
            long acc = 0;
            for (int e = seq.size() - 1; e >= 0; e--) {
                int[] ev = seq.get(e);
                int[] r = new int[ev.length / 2];
                for (int j = ev.length / 2 - 1; j >= 0; j--) { r[j] = (int) acc; acc += ev[j * 2 + 1]; }
                tmp[e] = r;
            }
            List<int[]> rs = new ArrayList<>();
            for (int[] r : tmp) rs.add(r);
            ruDB.add(rs);
        }
        return ruDB;
    }

    static String format(List<List<Integer>> pattern) {
        StringBuilder sb = new StringBuilder("<");
        for (List<Integer> ev : pattern) {
            sb.append("(");
            for (int i = 0; i < ev.size(); i++) { sb.append(ev.get(i)); if (i < ev.size() - 1) sb.append(' '); }
            sb.append(")");
        }
        return sb.append(">").toString();
    }
}
