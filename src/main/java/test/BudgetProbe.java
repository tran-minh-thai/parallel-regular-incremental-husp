package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoRHUSP;
import algorithms.SeqConverter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Measures the budget-only mode: no final size, no growth factor, no lookahead — the miner is told
 * nothing about the future at all. The heap budget is the single input, and the retention bound B is
 * an OUTPUT, read off {@link AlgoPRIncHUSP#emergentBound()} after the run.
 *
 * <p>Two things have to hold, and both are checked here rather than argued. Recall against the
 * budget is the trade-off curve: memory, a fact about the machine, exchanged for patterns. And the
 * guarantee must be exact on its class: every oracle pattern whose maxPer lies at or under the
 * reported B has to be present in the answer — the "class miss" column counts violations and must
 * read zero on every row.
 *
 * <pre>
 *   java -cp out test.BudgetProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03 A
 * </pre>
 */
public class BudgetProbe {

    // Tight budgets first: they are the rows that answer anything, and on dense data the unbounded
    // row is the known-infeasible cell of the trilemma -- it goes last so a run interrupted there has
    // already produced every useful line. A sixth argument overrides the list ("400,100").
    private static final int[] BUDGET_MB = {50, 100, 200, 400, 0};

    public static void main(String[] args) throws Exception {
        String seqFile = args[0], euiFile = args[1];
        double delta = Double.parseDouble(args[2]), rho = Double.parseDouble(args[3]);
        double[] ratios = args[4].equals("B") ? ExpConfig.SCEN_B : ExpConfig.SCEN_A;
        int[] budgets = BUDGET_MB;
        if (args.length > 5) {
            String[] parts = args[5].split(",");
            budgets = new int[parts.length];
            for (int i = 0; i < parts.length; i++) budgets[i] = Integer.parseInt(parts[i].trim());
        }

        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ratios);

        // Oracle WITH periods, so the class guarantee is checkable pattern by pattern.
        long totalUtil = ExpUtil.totalUtil(all);
        long minUtil = (long) Math.ceil(delta * totalUtil);
        int maxReg = (int) (rho * all.size());
        AlgoRHUSP oracle = new AlgoRHUSP();
        oracle.parallel = false;
        Map<String, Long> oraclePer = new HashMap<>();
        for (Map.Entry<String, long[]> e : oracle.mine(all, minUtil, maxReg).entrySet())
            oraclePer.put(SeqConverter.canonical(e.getKey()), e.getValue()[1]);

        System.out.printf("%s  dist=%s  N=%d  batches=%d  oracle=%d%n",
                ExpUtil.datasetTag(seqFile), args[4], all.size(), batches.size(), oraclePer.size());
        System.out.printf("%6s  %8s  %8s  %10s  %10s  %10s  %9s  %8s%n",
                "MB", "recall", "HS", "held", "B", "classMiss", "peak MB", "ms");

        for (int mb : budgets) {
            try {
                AlgoPRIncHUSP m = ExpConfig.newProposed(Runtime.getRuntime().availableProcessors());
                m.memoryBudgetMB = mb;                 // the single input; no hint is ever supplied
                m.floorPruneSeeds = System.getProperty("floorPrune") != null;
                // -DstartBound=22 pins the FIRST rung, so a run can be replayed with no failed
                // attempts before it -- the discriminating experiment between "the aborted rungs
                // leave corrupt state behind" and "steady-state budget mode itself loses patterns".
                int sb = Integer.getInteger("startBound", 0);
                if (sb > 0) m.growthFactor = sb / (rho * batches.get(0).size());
                PeakMemoryMeter meter = new PeakMemoryMeter();
                long t0 = System.currentTimeMillis();
                m.initialBuild(batches.get(0), delta, rho);
                for (int i = 1; i < batches.size(); i++) m.processBatch(batches.get(i));
                Map<String, long[]> res = new HashMap<>(m.getHighUtilityPatterns());
                long ms = System.currentTimeMillis() - t0;
                int held = m.trackedCount();
                int bound = m.emergentBound();
                m.close();
                double peak = meter.peakMB();
                meter.close();

                TreeSet<String> canon = new TreeSet<>();
                for (String k : res.keySet()) canon.add(SeqConverter.canonical(k));
                int hit = 0, classMiss = 0;
                java.util.List<String> violations = new java.util.ArrayList<>();
                for (Map.Entry<String, Long> e : oraclePer.entrySet()) {
                    boolean present = canon.contains(e.getKey());
                    if (present) hit++;
                    if (e.getValue() <= bound && !present) {              // guarantee violation
                        classMiss++;
                        if (violations.size() < 10) violations.add(e.getKey() + " per=" + e.getValue());
                    }
                }
                System.out.printf("%6s  %8.4f  %8d  %10d  %10s  %10d  %9.1f  %8d%n",
                        mb == 0 ? "none" : String.valueOf(mb),
                        oraclePer.isEmpty() ? 1.0 : (double) hit / oraclePer.size(),
                        res.size(), held,
                        bound == Integer.MAX_VALUE ? "inf" : String.valueOf(bound),
                        classMiss, peak, ms);
                for (String v : violations) System.out.println("        MISS " + v);
            } catch (Throwable t) {
                System.out.printf("%6s  %8s%n", mb == 0 ? "none" : String.valueOf(mb),
                        t.getClass().getSimpleName());
            }
        }
    }
}
