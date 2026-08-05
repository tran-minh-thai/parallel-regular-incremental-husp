package algorithms;

import java.util.Arrays;

/**
 * Open-addressing {@code int} hash set (linear probing); replaces {@code HashSet<Integer>} to
 * AVOID autoboxing/unboxing in the hot path (localCandidates). Keys stored directly in {@code int[]}.
 * Sentinel {@link #FREE} = {@link Integer#MIN_VALUE} (assumes no item equals MIN_VALUE).
 */
public final class IntHashSet {
    private static final int FREE = Integer.MIN_VALUE;
    private int[] keys;
    private int mask, size;

    public IntHashSet(int expected) {
        int c = capacityFor(expected);
        keys = new int[c]; Arrays.fill(keys, FREE); mask = c - 1;
    }

    public boolean add(int k) {
        int i = k & mask;
        while (keys[i] != FREE) { if (keys[i] == k) return false; i = (i + 1) & mask; }
        keys[i] = k;
        if (++size * 4 > keys.length * 3) resize();
        return true;
    }

    public boolean contains(int k) {
        int i = k & mask;
        while (keys[i] != FREE) { if (keys[i] == k) return true; i = (i + 1) & mask; }
        return false;
    }

    public boolean isEmpty() { return size == 0; }
    public int size() { return size; }

    /** Clear for REUSE (retains array capacity); avoids allocating a new set in the hot path. */
    public void clear() {
        if (size == 0) return;
        Arrays.fill(keys, FREE);
        size = 0;
    }

    /** Keys as an array (unordered). */
    public int[] toArray() {
        int[] a = new int[size]; int n = 0;
        for (int k : keys) if (k != FREE) a[n++] = k;
        return a;
    }

    private void resize() {
        int[] old = keys;
        keys = new int[old.length * 2]; Arrays.fill(keys, FREE); mask = keys.length - 1; size = 0;
        for (int k : old) if (k != FREE) add(k);
    }

    private static int capacityFor(int expected) {
        int c = 4; while (c * 3 < Math.max(1, expected) * 4) c <<= 1; return c;
    }
}
