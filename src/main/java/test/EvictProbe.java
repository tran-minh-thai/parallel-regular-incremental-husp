package test;

import algorithms.AlgoPRIncHUSP;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs one configuration twice, with {@link AlgoPRIncHUSP#evictPermanentlyIrregular} off and on, and
 * reports what changed.
 *
 * <p>Dropping a pattern whose fixed period has passed rho*N_final is sound on paper: the period only
 * grows, so the pattern is irregular at this batch and at every later one. This probe is what turns
 * that into evidence. It prints the answer size, the recall against a full re-mine, the peak heap and
 * the tracked-set size for both settings, and then compares the two answers key by key. The answers
 * must be IDENTICAL -- the rule may reclaim memory, never change a result.
 *
 * <pre>
 *   java -cp out test.EvictProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03 A
 *   java -cp out test.EvictProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03 B true
 * </pre>
 * The fifth argument selects the batch distribution (A uniform, B increasing). A sixth argument runs
 * only that one setting, for the case where the other exhausts the heap and would otherwise take the
 * probe down with it; each setting is caught separately for the same reason.
 */
public class EvictProbe {

    public static void main(String[] args) throws Exception {
        String seqFile = args[0], euiFile = args[1];
        double delta = Double.parseDouble(args[2]), rho = Double.parseDouble(args[3]);
        double[] ratios = args[4].equals("B") ? ExpConfig.SCEN_B : ExpConfig.SCEN_A;
        boolean[] settings = args.length > 5
                ? new boolean[]{Boolean.parseBoolean(args[5])}
                : new boolean[]{false, true};

        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ratios);
        System.out.printf("%s  dist=%s  delta=%s rho=%s  N=%d%n",
                ExpUtil.datasetTag(seqFile), args[4], args[2], args[3], all.size());

        Set<String> oracle = ExpUtil.oracleCanon(all, delta, rho);
        System.out.println("oracle = " + oracle.size() + " patterns");

        boolean noHint = System.getProperty("nohint") != null;
        if (noHint) System.out.println("NO HINT: the final size is withheld, as in an open-ended stream");

        Map<String, long[]> reference = null;
        for (boolean evict : settings) {
            try {
                AlgoPRIncHUSP m = ExpConfig.newProposed(Runtime.getRuntime().availableProcessors());
                m.evictPermanentlyIrregular = evict;
                long[] phase = new long[3];
                double[] phaseMem = new double[3];
                int[] held = new int[2];
                PeakMemoryMeter meter = new PeakMemoryMeter();
                Map<String, long[]> res;
                if (noHint) {
                    // Same sequence as ExpUtil.run, minus hintTotalSequences: the miner never learns
                    // how much data is still to come, which is the open-ended case.
                    m.initialBuild(batches.get(0), delta, rho);
                    held[0] = m.trackedCount();
                    for (int i = 1; i < batches.size(); i++) m.processBatch(batches.get(i));
                    res = new java.util.HashMap<>(m.getHighUtilityPatterns());
                    held[1] = m.trackedCount();
                    m.close();
                } else {
                    res = ExpUtil.run(m, batches, delta, rho, phase, meter, phaseMem, held);
                }
                double peak = meter.peakMB();
                meter.close();
                System.out.printf("  evict=%-5s  HS=%-6d recall=%.4f  peak=%7.1f MB  held(seed/end)=%d/%d"
                                + "  evicted=%d  ms=%d%n",
                        evict, res.size(), ExpUtil.coverage(res, oracle), peak, held[0], held[1],
                        m.evictedCount(), phase[0] + phase[1] + phase[2]);
                if (!evict) {
                    reference = res;
                } else if (reference != null) {
                    Set<String> off = new TreeSet<>(reference.keySet());
                    Set<String> on = new TreeSet<>(res.keySet());
                    System.out.println("  answers identical: " + off.equals(on)
                            + (off.equals(on) ? ""
                               : "  (dropped=" + missing(off, on) + " added=" + missing(on, off) + ")"));
                }
            } catch (Throwable t) {
                System.out.printf("  evict=%-5s  FAILED: %s%n", evict, t.getClass().getSimpleName());
            }
        }
    }

    /** How many keys of {@code a} are absent from {@code b}. */
    private static int missing(Set<String> a, Set<String> b) {
        Set<String> only = new TreeSet<>(a);
        only.removeAll(b);
        return only.size();
    }
}
