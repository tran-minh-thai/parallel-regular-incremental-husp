package test;

import algorithms.AlgoRHUSPMinerParallel;

import java.util.List;

/**
 * Mine the old part of a stream once per mode, with the engine settings of the proposal's seed and of a full
 * re-mine, at the same threshold (ceil(delta * U(D_old)), the seed threshold with buffer factor 1) and the same
 * absolute regularity bound, and print time, patterns and the engine's search counters.
 *
 *     java -cp out test.SeedProbe seq eui delta B dOld k threads mode[,mode...]
 *
 * Modes: seed (seed mode with the floor prune, as the proposal seeds under an absolute bound), strict (as a full
 * re-mine), seed-nofloor (seed mode without the floor prune). A suffix ":x<f>" multiplies the threshold by f; a suffix
 * ":b<n>" mines the old part plus the next n batches at delta times their utility (what the first re-mine sees for n = 1).
 * Feasibility diagnosis only.
 */
public class SeedProbe {
    public static void main(String[] args) throws Exception {
        String seq = args[0], eui = args[1];
        double delta = Double.parseDouble(args[2]);
        int b = Integer.parseInt(args[3]);
        double dOld = Double.parseDouble(args[4]);
        int k = Integer.parseInt(args[5]);
        int threads = Integer.parseInt(args[6]);
        String[] modes = args[7].split(",");
        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ExpConfig.fineRatios(k, dOld));
        List<List<int[]>> old = batches.get(0);
        long u = ExpUtil.totalUtil(old);
        long theta = (long) Math.ceil(delta * u);
        System.out.printf("%s N=%d old=%d U(old)=%d threshold=%d B=%d threads=%d%n",
                ExpUtil.datasetTag(seq), all.size(), old.size(), u, theta, b, threads);
        System.out.println("mode          ms  patterns  explored_nodes  candidates  pruned_eucs  pruned_bound  pruned_node_cond  pruned_regularity");
        for (String spec : modes) {
            String mode = spec.split(":")[0];
            double factor = 1.0;
            List<List<int[]>> db = old;
            for (String part : spec.split(":")) {
                if (part.startsWith("x")) factor = Double.parseDouble(part.substring(1));
                if (part.startsWith("b")) {
                    db = new java.util.ArrayList<>(old);
                    for (int i = 1; i <= Integer.parseInt(part.substring(1)); i++) db.addAll(batches.get(i));
                }
            }
            long th = (long) Math.ceil(factor * Math.ceil(delta * ExpUtil.totalUtil(db)));
            AlgoRHUSPMinerParallel e = new AlgoRHUSPMinerParallel();
            e.numThreads = threads;
            e.parallelStrategy = AlgoRHUSPMinerParallel.STRAT_RDLB;
            e.denseBuffers = true;
            e.useEUCS = true;
            e.boundMode = AlgoRHUSPMinerParallel.BOUND_LA_PEU;
            e.useRegPruning = true;
            e.seedMode = mode.startsWith("seed");
            e.floorPrune = mode.equals("seed");
            e.forcedMinUtil = th;
            e.forcedMaxReg = b;
            e.measureMemory = false;
            long t0 = System.nanoTime();
            e.runAlgorithmInMemory(db, delta, 0.0);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("%-12s %7d %9d %15d %11d %12d %13d %17d %18d  n=%d threshold=%d%n", spec, ms, e.finalPatterns.size(), e.exploredNodes,
                    e.candidateCount, e.prunedEucs, e.prunedBound, e.prunedNodeCond, e.prunedRegularity, db.size(), th);
        }
    }
}
