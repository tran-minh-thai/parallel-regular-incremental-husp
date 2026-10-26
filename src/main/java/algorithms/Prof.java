package algorithms;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase profiler for the parallelism audit. Off unless {@code -Dprof=true}; when off every call is a
 * single static branch. Each phase records wall time and process CPU time, so the ratio
 * cpu / (wall * threads) tells how many cores a phase actually kept busy: a phase near 1/threads is
 * running on one core, which is the bottleneck the audit looks for. CPU is summed over live Java
 * threads only, so GC and JIT compiler threads (hidden from the thread bean) do not inflate it; a
 * thread that dies inside a phase loses its share, so the figure is a lower bound.
 * Timers are taken on the coordinating thread only (phases are never nested across threads).
 */
public final class Prof {
    public static final boolean ON = Boolean.getBoolean("prof");
    private static final java.lang.management.ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    private static final Map<String, long[]> ACC = new TreeMap<>();   // name -> {calls, wallNs, cpuNs}

    private Prof() {}

    /** Start a phase: returns {wallNs, cpuNs}, or null when profiling is off. */
    public static long[] start() {
        return ON ? new long[]{System.nanoTime(), javaThreadsCpu()} : null;
    }

    /** Sum of CPU time over live Java threads; a thread first seen later counts from zero. */
    private static long javaThreadsCpu() {
        long sum = 0;
        for (long id : TMX.getAllThreadIds()) {
            long c = TMX.getThreadCpuTime(id);
            if (c > 0) sum += c;
        }
        return sum;
    }

    public static synchronized void stop(String name, long[] t0) {
        if (t0 == null) return;
        long wall = System.nanoTime() - t0[0], cpu = javaThreadsCpu() - t0[1];
        long[] a = ACC.computeIfAbsent(name, k -> new long[3]);
        a[0]++; a[1] += wall; a[2] += cpu;
    }

    public static synchronized void reset() { ACC.clear(); }

    /** One line per phase: calls, wall ms, cpu ms, cores busy = cpu / wall, efficiency = busy / threads. */
    public static synchronized String report(int threads) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-28s %7s %11s %11s %7s %6s%n", "phase", "calls", "wall_ms", "cpu_ms", "cores", "eff"));
        for (Map.Entry<String, long[]> e : ACC.entrySet()) {
            long[] a = e.getValue();
            double wall = a[1] / 1e6, cpu = a[2] / 1e6, cores = wall > 0 ? cpu / wall : 0;
            if (e.getKey().endsWith(".wallOnly"))   // spans a pool whose threads die inside it: CPU is not readable
                sb.append(String.format("%-28s %7d %11.1f %11s %7s %6s%n", e.getKey(), a[0], wall, "n/a", "n/a", "n/a"));
            else
                sb.append(String.format("%-28s %7d %11.1f %11.1f %7.2f %6.2f%n", e.getKey(), a[0], wall, cpu, cores, cores / threads));
        }
        return sb.toString();
    }
}
