package test;

import algorithms.AlgoPRIncHUSP;
import algorithms.AlgoRHUSP;
import algorithms.IncrementalHUSPMiner;
import algorithms.SeqConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Randomised exactness check of the query policies on many tiny synthetic databases.
 *
 * <p>Real datasets rarely hold a pattern exactly on a threshold boundary, so a discovery threshold
 * that is one unit too high stays invisible there. Tiny databases with small integer utilities hit
 * those boundaries often, and they contain multi-item events, so itemset extensions are exercised as
 * well. For each seed a database is drawn, split into {@code k} batches, queried after every batch by
 * each policy, and compared with the oracle on the same prefix.
 *
 * <pre>
 *   java -cp out test.QueryScheduleStress 500            # 500 random databases
 *   java -Dfault=1 -cp out test.QueryScheduleStress 500  # must report failures
 * </pre>
 * Exit status is non-zero when any query disagrees or no query was compared.
 */
public class QueryScheduleStress {

    public static void main(String[] args) throws Exception {
        int trials = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        AlgoPRIncHUSP.FAULT = Integer.getInteger("fault", 0);
        String[] policies = System.getProperty("policies", "keep-sfx,reset-sfx,keep-sfx-gf,keep-sfx-gf-l0.5").split(",");
        int compared = 0, failing = 0, nonEmpty = 0, multiItemSeqs = 0;
        Map<String, Integer> failsByPolicy = new TreeMap<>();
        for (int seed = 1; seed <= trials; seed++) {
            Random r = new Random(seed);
            int n = 20 + r.nextInt(41);                       // 20..60 sequences
            int alphabet = 4 + r.nextInt(5);                  // 4..8 items
            double delta = 0.02 + 0.08 * r.nextDouble();
            int absB = 3 + r.nextInt(10);
            int k = 3 + r.nextInt(6);
            List<List<int[]>> all = randomDb(r, n, alphabet);
            for (List<int[]> s : all) for (int[] e : s) if (e.length > 2) { multiItemSeqs++; break; }
            List<List<List<int[]>>> batches = ExpUtil.split(all, ExpConfig.fineRatios(k, 0.25));
            ExpConfig.absoluteMode = true;
            ExpConfig.absoluteB = absB;

            List<Map<String, long[]>> oracle = new ArrayList<>();
            List<List<int[]>> prefix = new ArrayList<>();
            for (int b = 0; b < batches.size(); b++) {
                prefix.addAll(batches.get(b));
                long minUtil = (long) Math.ceil(delta * ExpUtil.totalUtil(prefix));
                Map<String, long[]> canon = new HashMap<>();
                AlgoRHUSP o = new AlgoRHUSP(); o.parallel = false;
                for (Map.Entry<String, long[]> e : o.mine(new ArrayList<>(prefix), minUtil, absB).entrySet())
                    canon.put(SeqConverter.canonical(e.getKey()), e.getValue());
                oracle.add(canon);
            }
            for (String policy : policies) {
                IncrementalHUSPMiner mm = QueryScheduleProbe.build(policy, 2, absB);
                mm.initialBuild(batches.get(0), delta, 0.0);
                for (int b = 1; b < batches.size(); b++) {
                    mm.processBatch(batches.get(b));
                    Map<String, long[]> res = new HashMap<>(mm.getHighUtilityPatterns());
                    int[] d = QueryScheduleProbe.diff(res, oracle.get(b));
                    compared++;
                    if (!oracle.get(b).isEmpty()) nonEmpty++;
                    if (d[0] + d[1] + d[2] + d[3] > 0) {
                        failing++;
                        failsByPolicy.merge(policy, 1, Integer::sum);
                        if (failing <= 5)
                            System.out.printf("  FAIL seed=%d policy=%s batch=%d oracle=%d miss=%d extra=%d uErr=%d pErr=%d%n",
                                    seed, policy, b, oracle.get(b).size(), d[0], d[1], d[2], d[3]);
                    }
                }
                mm.close();
            }
        }
        System.out.printf("fault=%d trials=%d sequences-with-multi-item-events=%d compared-queries=%d "
                        + "with-non-empty-oracle=%d failing=%d %s%n",
                AlgoPRIncHUSP.FAULT, trials, multiItemSeqs, compared, nonEmpty, failing, failsByPolicy);
        if (compared == 0 || nonEmpty == 0 || failing > 0) System.exit(1);
    }

    /** Random quantitative sequence database: events of 1..3 distinct items, utilities 1..6. */
    static List<List<int[]>> randomDb(Random r, int n, int alphabet) {
        List<List<int[]>> db = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            int len = 1 + r.nextInt(5);
            List<int[]> seq = new ArrayList<>();
            for (int e = 0; e < len; e++) {
                int size = 1 + r.nextInt(3);
                java.util.TreeSet<Integer> items = new java.util.TreeSet<>();
                while (items.size() < Math.min(size, alphabet)) items.add(1 + r.nextInt(alphabet));
                int[] ev = new int[items.size() * 2];
                int i = 0;
                for (int it : items) { ev[i++] = it; ev[i++] = 1 + r.nextInt(6); }
                seq.add(ev);
            }
            db.add(seq);
        }
        return db;
    }
}
