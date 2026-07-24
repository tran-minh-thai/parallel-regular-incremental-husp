package test;

/**
 * One peak-heap meter for every miner, so their memory figures are comparable.
 *
 * Each miner used to measure its own peak, and not the same way: the parallel static engine ran a
 * periodic sampler thread, the incremental miner recorded the heap at a couple of fixed points, the
 * re-mine baselines at a few others. A point sample taken at chosen moments can miss the true peak,
 * and it misses it by a different amount in each miner, so the peak-memory column was comparing
 * numbers from different instruments. Reading the peak from this meter instead — started and stopped
 * by the harness around every run — makes the instrument identical regardless of what the miner does
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
    private volatile long peakBytes;

    PeakMemoryMeter() {
        Runtime rt = Runtime.getRuntime();
        peakBytes = rt.totalMemory() - rt.freeMemory();   // count what is already resident at the start
        sampler = new Thread(() -> {
            while (running) {
                long cur = rt.totalMemory() - rt.freeMemory();
                if (cur > peakBytes) peakBytes = cur;
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

    /** Stops the sampler; call {@link #peakMB()} first if the value is still needed. */
    @Override public void close() {
        running = false;
        sampler.interrupt();
        try { sampler.join(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
