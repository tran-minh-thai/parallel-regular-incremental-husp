package test;

import algorithms.AlgoRHUSPMinerParallel;

/**
 * Checks the engine's heap valve in both modes against the two situations that tell them apart.
 *
 *   garbage above the valve:   the raw valve must abort; the live valve must not, and must collect exactly once.
 *   live data above the valve: both must abort.
 *
 * The valve is set halfway between the heap before and after allocating 256 MB, so the raw reading is
 * above it and the live heap is below it only when the allocation is garbage. A case whose garbage was
 * collected before the check cannot tell the modes apart; it is reported as not testable and does not
 * count. Exit status is non-zero when a case fails or fewer than all four cases could be tested.
 *
 *     java -Xmx2g -cp out test.HeapValveCheck            (-Dheapvalve.fault=1 must make it fail)
 */
public class HeapValveCheck {
    static volatile Object sink;

    static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static byte[][] allocate() {
        byte[][] g = new byte[64][];
        for (int i = 0; i < g.length; i++) g[i] = new byte[4 << 20];
        return g;
    }

    public static void main(String[] args) {
        int tested = 0, failing = 0;
        for (boolean garbage : new boolean[]{true, false}) {
            for (boolean live : new boolean[]{false, true}) {
                String name = (garbage ? "garbage" : "live data") + " above the valve, " + (live ? "live" : "raw") + " valve";
                System.gc();
                long base = used();
                byte[][] g = allocate();
                if (garbage) g = null; else sink = g;
                long raw = used();
                AlgoRHUSPMinerParallel e = new AlgoRHUSPMinerParallel();
                e.abortHeapBytes = base + (raw - base) / 2;
                e.abortHeapLive = live;
                if (used() <= e.abortHeapBytes) {
                    System.out.println("  " + name + ": allocation collected before the check, not testable");
                    continue;
                }
                boolean aborted = false;
                try {
                    e.checkHeapValve();
                } catch (AlgoRHUSPMinerParallel.HeapBudgetExceeded x) {
                    aborted = true;
                }
                boolean wantAbort = !garbage || !live;
                int wantCollections = live ? 1 : 0;
                boolean ok = aborted == wantAbort && e.heapValveCollections.get() == wantCollections;
                tested++;
                if (!ok) failing++;
                System.out.printf("  %-45s aborted=%s (want %s) collections=%d (want %d) %s%n", name, aborted, wantAbort,
                        e.heapValveCollections.get(), wantCollections, ok ? "ok" : "FAILED");
                sink = null;
            }
        }
        System.out.printf("heap valve cases tested=%d failing=%d%n", tested, failing);
        System.exit(failing > 0 || tested < 4 ? 1 : 0);
    }
}
