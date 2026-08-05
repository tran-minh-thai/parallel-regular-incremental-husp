package test;

/**
 * One peak-heap meter for every miner, so their memory figures are comparable.
 *
 * Each miner used to measure its own peak, and not the same way: the parallel static engine ran a
 * periodic sampler thread, the incremental miner recorded the heap at a couple of fixed points, the
 * re-mine baselines at a few others. A point sample taken at chosen moments can miss the true peak,
 * and it misses it by a different amount in each miner, so the peak-memory column was comparing
 * numbers from different instruments. Reading the peak from this meter instead, started and stopped
 * by the harness around every run, makes the instrument identical regardless of what the miner does
 * internally.
 *
 * It samples {@code totalMemory() - freeMemory()}, the same quantity the miners sampled, on a daemon
 * thread every {@link #INTERVAL_MS} ms. Sampling cannot catch an allocation spike shorter than the
 * interval, but it catches it the same way for every miner, which is the property that matters for a
 * comparison. Absolute peak accuracy would need a JVM allocation profiler and is not the point here.
 */
final class PeakMemoryMeter implements AutoCloseable {
    static final long INTERVAL_MS = 20;

    private final Thread sampler;
    private volatile boolean running = true;
    private volatile long peakBytes;         // whole run; never reset, so the total column is unaffected
    private volatile long windowPeakBytes;   // since the last phase mark

    PeakMemoryMeter() {
        Runtime rt = Runtime.getRuntime();
        peakBytes = rt.totalMemory() - rt.freeMemory();   // count what is already resident at the start
        windowPeakBytes = peakBytes;
        sampler = new Thread(() -> {
            while (running) {
                long cur = rt.totalMemory() - rt.freeMemory();
                if (cur > peakBytes) peakBytes = cur;
                if (cur > windowPeakBytes) windowPeakBytes = cur;
                try { Thread.sleep(INTERVAL_MS); } catch (InterruptedException e) { return; }
            }
        }, "peak-memory-meter");
        sampler.setDaemon(true);
        sampler.start();
    }

    double peakMB() {
        long cur = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        if (cur > peakBytes) peakBytes = cur;             // one final reading in case the peak is now
        return peakBytes / (1024.0 * 1024.0);
    }

    /**
     * Peak since the previous mark (or since construction), after which the window restarts from the
     * heap's current level. Called at each phase boundary, this splits one run's peak into per-phase
     * peaks and answers which phase actually needs the heap, which the single whole-run figure cannot,
     * and a cell that dies of exhaustion leaves no breakdown at all.
     *
     * <p>The value is the highest level reached DURING the window, not the phase's own contribution:
     * memory still held from an earlier phase counts toward it. That is the intended reading, since
     * what has to fit is the level, not the increment. It follows that the phase figures do not sum
     * to the total; each is bounded by it, and the largest of them approaches it.
     *
     * <p>{@link #peakMB()} is unaffected, since the whole-run accumulator is never reset, so the existing
     * peak column keeps its meaning across this change.
     */
    double markPhaseMB() {
        long cur = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        // The reading here is fresh, so it can exceed anything the sampler has recorded in the up-to
        // INTERVAL_MS since its last pass. Feed it to the whole-run accumulator as well, or a phase
        // figure can come out ABOVE the total it is part of.
        if (cur > peakBytes) peakBytes = cur;
        long p = Math.max(windowPeakBytes, cur);
        windowPeakBytes = cur;              // restart the window from what is resident now
        return p / (1024.0 * 1024.0);
    }

    /** Stops the sampler; call {@link #peakMB()} first if the value is still needed. */
    @Override public void close() {
        running = false;
        sampler.interrupt();
        try { sampler.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
