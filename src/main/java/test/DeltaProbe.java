package test;

import algorithms.IncrementalHUSPMiner;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.Map;

/**
 * δ feasibility probe: run the PROPOSED miner (P-RIncHUSP, adaptive buffer, T = all cores) ONCE on one
 * dataset for a given δ, and report TRUE peak heap (JVM MXBean, not the sampled meter), #HS and #SHS.
 * If the run OOMs the JVM simply dies (no "OK" line) — the {@code tune_delta.sh} sweep runs each δ in a
 * fresh JVM so one OOM does not poison the others.
 * <p>
 * Distribution defaults to <b>B-Increasing (10/20/30/40)</b> — the small first batch is the
 * memory-worst case, so a δ that fits here fits the whole S1–S4 suite.
 * <p>Args: {@code <seqFile> <euiFile> <δ> [ρ=0.30] [threads=allCores] [dist=B|A]}.
 */
public class DeltaProbe {
    static long peakHeapMB() {
        long u = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP && p.getPeakUsage() != null) u += p.getPeakUsage().getUsed();
        return u / 1048576;
    }

    public static void main(String[] a) throws Exception {
        String seq = a[0], eui = a[1];
        double delta = Double.parseDouble(a[2]);
        double rho   = a.length > 3 ? Double.parseDouble(a[3]) : 0.30;
        int    T     = a.length > 4 ? Integer.parseInt(a[4]) : Runtime.getRuntime().availableProcessors();
        String dist  = a.length > 5 ? a[5] : "B";
        double[] ratios = dist.equalsIgnoreCase("A") ? ExpConfig.SCEN_A : ExpConfig.SCEN_B;

        List<List<int[]>> all = ExpUtil.loadAll(seq, eui);
        List<List<List<int[]>>> b = ExpUtil.split(all, ratios);

        IncrementalHUSPMiner m = ExpConfig.newProposed(T);   // same COMBINED adaptive config as the suite
        long t0 = System.currentTimeMillis();
        Map<String, long[]> hs = ExpUtil.run(m, b, delta, rho);   // initialBuild + 3 processBatch; closes m
        long ms = System.currentTimeMillis() - t0;

        System.out.printf("delta=%.4f dist=%s T=%d -> OK  HS=%d  SHS=%d  peak=%d MB  time=%d ms%n",
                delta, dist, T, hs.size(), m.bufferedCount(), peakHeapMB(), ms);
    }
}
