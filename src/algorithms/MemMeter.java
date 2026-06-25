package algorithms;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Reliably measures PEAK HEAP MEMORY of a single algorithm run within a shared JVM.
 *
 * Why not Runtime.totalMemory()-freeMemory(): in a long-lived JVM running several
 * algorithms in sequence, that metric = (committed heap) - (free), so it INCLUDES
 * garbage not yet reclaimed by GC from previous runs. With large -Xms/-Xmx, GC runs
 * rarely, so "used" only grows and is dominated by shared data structures (e.g. the
 * loaded database) -> the measured heap peak is NEARLY EQUAL across algorithms, hiding
 * real differences.
 *
 * Correct approach: each HEAP-type MemoryPoolMXBean has its own PEAK counter that can
 * be RESET.
 *   1) reset(): run GC a few times to clear the previous run's garbage, then call
 *      resetPeakUsage() on every heap pool -> the peak baseline returns to the current
 *      "live" level (isolated from history).
 *   2) peakBytes(): sum of getPeakUsage().getUsed() over the heap pools -> the true peak
 *      of THIS run (live + transient allocations produced during the run).
 *
 * Call reset() immediately before measurement starts (after loading shared data to
 * exclude the data baseline, or before loading to include the data), and peakBytes()
 * after the run completes.
 */
public final class MemMeter {
    private MemMeter() {}

    /** Run GC to clear the previous run's garbage, then reset the peak baseline of every heap pool to the current level. */
    public static void reset() {
        gc();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                try { p.resetPeakUsage(); } catch (UnsupportedOperationException ignored) {}
            }
        }
    }

    /** Heap peak (bytes) since the most recent reset(): sum of the heap pool peaks. */
    public static long peakBytes() {
        long sum = 0;
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean p : pools) {
            if (p.getType() == MemoryType.HEAP) {
                MemoryUsage u = p.getPeakUsage();
                if (u != null) sum += u.getUsed();
            }
        }
        return sum;
    }

    public static double peakMB() { return peakBytes() / (1024.0 * 1024.0); }

    private static void gc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
