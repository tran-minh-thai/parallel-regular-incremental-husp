package algorithms;

import java.util.Arrays;

/**
 * Open-addressing {@code int->long} hash map (linear probing); replaces {@code HashMap<Integer,Long>}
 * to AVOID autoboxing and {@code Long} object headers in the hot path (LA-PEU accumulation in
 * localCandidates). Keys and values stored directly in {@code int[]/long[]}.
 */
public final class IntLongHashMap {
    private static final int FREE = Integer.MIN_VALUE;
    private int[]  keys;
    private long[] vals;
    private int mask, size;

    public IntLongHashMap(int expected) {
        int c = capacityFor(expected);
        keys = new int[c]; Arrays.fill(keys, FREE); vals = new long[c]; mask = c - 1;
    }

    /** Add {@code delta} to the value of {@code k} (initializes to 0 if absent). */
    public void addTo(int k, long delta) {
        int i = k & mask;
        while (keys[i] != FREE) { if (keys[i] == k) { vals[i] += delta; return; } i = (i + 1) & mask; }
        keys[i] = k; vals[i] = delta;
        if (++size * 4 > keys.length * 3) resize();
    }

    /** Keep the LARGEST value for key {@code k} (dedup by end-position in max-measure). */
    public void putMax(int k, long v) {
        int i = k & mask;
        while (keys[i] != FREE) { if (keys[i] == k) { if (v > vals[i]) vals[i] = v; return; } i = (i + 1) & mask; }
        keys[i] = k; vals[i] = v;
        if (++size * 4 > keys.length * 3) resize();
    }

    public boolean isEmpty()      { return size == 0; }

    // ---- iterate entries (empty slot = FREE) ----
    public int  slotCount()       { return keys.length; }
    public boolean occupied(int i){ return keys[i] != FREE; }
    public int  keyAt(int i)      { return keys[i]; }
    public long valAt(int i)      { return vals[i]; }

    /** Clear for REUSE (retains capacity); avoids allocating a new map in the hot path. */
    public void clear() {
        if (size == 0) return;
        Arrays.fill(keys, FREE);
        size = 0;
    }

    public long get(int k, long def) {
        int i = k & mask;
        while (keys[i] != FREE) { if (keys[i] == k) return vals[i]; i = (i + 1) & mask; }
        return def;
    }

    private void resize() {
        int[] ok = keys; long[] ov = vals; int n = ok.length * 2;
        keys = new int[n]; Arrays.fill(keys, FREE); vals = new long[n]; mask = n - 1; size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != FREE) addTo(ok[i], ov[i]);
    }

    private static int capacityFor(int expected) {
        int c = 4; while (c * 3 < Math.max(1, expected) * 4) c <<= 1; return c;
    }
}
