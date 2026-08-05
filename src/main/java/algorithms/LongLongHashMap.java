package algorithms;

import java.util.Arrays;

/**
 * Open-addressing {@code long->long} map (linear probing) for the content-driven maintain's dominance
 * memo: key = (nodeId&lt;&lt;32)|(event&lt;&lt;16)|itemIdx, value = largest prefix utility seen at that state.
 * Avoids {@code HashMap<Long,Long>} autoboxing in the hot traversal. Keys are non-negative
 * (nodeId&ge;0), so {@link Long#MIN_VALUE} is a safe empty sentinel.
 */
public final class LongLongHashMap {
    private static final long FREE = Long.MIN_VALUE;
    private long[] keys, vals;
    private int mask, size;

    public LongLongHashMap(int expected) {
        int c = capacityFor(expected);
        keys = new long[c]; Arrays.fill(keys, FREE); vals = new long[c]; mask = c - 1;
    }

    /**
     * Dominance check + record. Returns {@code true} if {@code k} is already mapped to a value
     * {@code >= v} (the current state is dominated → caller prunes); otherwise records {@code v} as the
     * new max for {@code k} and returns {@code false}.
     */
    public boolean dominated(long k, long v) {
        int i = (int) (mix(k) & mask);
        while (keys[i] != FREE) {
            if (keys[i] == k) { if (vals[i] >= v) return true; vals[i] = v; return false; }
            i = (i + 1) & mask;
        }
        keys[i] = k; vals[i] = v;
        if (++size * 4 > keys.length * 3) resize();
        return false;
    }

    /** Clear for REUSE (retains capacity). */
    public void clear() {
        if (size == 0) return;
        Arrays.fill(keys, FREE);
        size = 0;
    }

    private void resize() {
        long[] ok = keys, ov = vals; int n = ok.length * 2;
        keys = new long[n]; Arrays.fill(keys, FREE); vals = new long[n]; mask = n - 1; size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != FREE) put(ok[i], ov[i]);
    }

    private void put(long k, long v) {
        int i = (int) (mix(k) & mask);
        while (keys[i] != FREE) { if (keys[i] == k) { vals[i] = v; return; } i = (i + 1) & mask; }
        keys[i] = k; vals[i] = v; size++;
    }

    /** Fibonacci-style mix; the low bits of the raw key are the tiny itemIdx, poor for masking alone. */
    private static long mix(long k) {
        k *= 0x9E3779B97F4A7C15L;
        return k ^ (k >>> 29);
    }

    private static int capacityFor(int expected) {
        int c = 8; while (c * 3 < Math.max(1, expected) * 4) c <<= 1; return c;
    }
}
