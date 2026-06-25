package common;

import java.util.Arrays;

/**
 * Inline "fastutil-style" primitive-keyed hash maps for HUSPM-Adapt.
 *
 * Replaces HashMap<Integer, V> / HashMap<Integer, Double> on the recursive hot path:
 *  - No key boxing (int) -> each lookup creates no Integer.
 *  - Open-addressing + linear probing -> cache-friendly, no Node object garbage.
 *  - Power-of-two growth + Murmur3 finalizer -> fewer collisions than the default HashMap.
 *
 * Deliberately does NOT support iterator/keySet/entrySet to avoid allocation on the
 * hot path. Only exposes put / get / remove / clear / addTo (for IntDoubleMap).
 *
 * Reserved key: Integer.MIN_VALUE (used as the "empty slot" sentinel). Must not be
 * used as a real key — in this domain seqId/itemId are always >= 0, so it is safe.
 */
public final class PrimitiveMaps {
    private PrimitiveMaps() {}

    private static final int FREE = Integer.MIN_VALUE;

    private static int hash(int k) {
        // Murmur3 finalizer — spreads sequential int keys (seqId, itemId) well.
        k ^= k >>> 16;
        k *= 0x85ebca6b;
        k ^= k >>> 13;
        k *= 0xc2b2ae35;
        k ^= k >>> 16;
        return k;
    }

    private static int roundUpPow2(int n) {
        if (n <= 16) return 16;
        int p = Integer.highestOneBit(n - 1) << 1;
        return Math.max(p, 16);
    }

    // ====================================================================
    //  int → V (no autoboxing of key)
    // ====================================================================
    public static final class IntObjectMap<V> {
        private int[] keys;
        private Object[] values;
        private int size;
        private int mask;
        private int threshold;

        public IntObjectMap() { this(16); }

        public IntObjectMap(int initialCap) {
            int cap = roundUpPow2(initialCap);
            keys = new int[cap];
            values = new Object[cap];
            Arrays.fill(keys, FREE);
            mask = cap - 1;
            threshold = (cap * 3) >>> 2; // ~75% load factor
        }

        public int size() { return size; }
        public boolean isEmpty() { return size == 0; }

        @SuppressWarnings("unchecked")
        public V get(int key) {
            if (key == FREE) return null;
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) return null;
                if (k == key) return (V) values[i];
                i = (i + 1) & mask;
            }
        }

        public boolean containsKey(int key) {
            if (key == FREE) return false;
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) return false;
                if (k == key) return true;
                i = (i + 1) & mask;
            }
        }

        @SuppressWarnings("unchecked")
        public V put(int key, V value) {
            if (key == FREE) throw new IllegalArgumentException("Reserved key Integer.MIN_VALUE");
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) {
                    keys[i] = key;
                    Object old = values[i];
                    values[i] = value;
                    size++;
                    if (size > threshold) resize();
                    return (V) old;
                }
                if (k == key) {
                    Object old = values[i];
                    values[i] = value;
                    return (V) old;
                }
                i = (i + 1) & mask;
            }
        }

        /** Get-or-compute, but supplier is invoked at most once. */
        @SuppressWarnings("unchecked")
        public V computeIfAbsent(int key, java.util.function.IntFunction<V> supplier) {
            if (key == FREE) throw new IllegalArgumentException("Reserved key Integer.MIN_VALUE");
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) {
                    V v = supplier.apply(key);
                    keys[i] = key;
                    values[i] = v;
                    size++;
                    if (size > threshold) resize();
                    return v;
                }
                if (k == key) return (V) values[i];
                i = (i + 1) & mask;
            }
        }

        @SuppressWarnings("unchecked")
        public V remove(int key) {
            if (key == FREE) return null;
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) return null;
                if (k == key) {
                    Object old = values[i];
                    // backward-shift deletion (keeps open-addressing intact)
                    int j = i;
                    while (true) {
                        int next = (j + 1) & mask;
                        int kn = keys[next];
                        if (kn == FREE) break;
                        int target = hash(kn) & mask;
                        if (((next - target) & mask) >= ((next - j) & mask)) {
                            keys[j] = kn;
                            values[j] = values[next];
                            j = next;
                        } else {
                            break;
                        }
                    }
                    keys[j] = FREE;
                    values[j] = null;
                    size--;
                    return (V) old;
                }
                i = (i + 1) & mask;
            }
        }

        public void clear() {
            Arrays.fill(keys, FREE);
            Arrays.fill(values, null);
            size = 0;
        }

        @SuppressWarnings("unchecked")
        private void resize() {
            int oldCap = keys.length;
            int newCap = oldCap << 1;
            int[] oldK = keys;
            Object[] oldV = values;
            keys = new int[newCap];
            values = new Object[newCap];
            Arrays.fill(keys, FREE);
            mask = newCap - 1;
            threshold = (newCap * 3) >>> 2;
            size = 0;
            for (int i = 0; i < oldCap; i++) {
                int k = oldK[i];
                if (k != FREE) put(k, (V) oldV[i]);
            }
        }
    }

    // ====================================================================
    //  int → double (no autoboxing of key OR value)
    // ====================================================================
    public static final class IntDoubleMap {
        private int[] keys;
        private double[] values;
        private int size;
        private int mask;
        private int threshold;

        public IntDoubleMap() { this(16); }

        public IntDoubleMap(int initialCap) {
            int cap = roundUpPow2(initialCap);
            keys = new int[cap];
            values = new double[cap];
            Arrays.fill(keys, FREE);
            mask = cap - 1;
            threshold = (cap * 3) >>> 2;
        }

        public int size() { return size; }
        public boolean isEmpty() { return size == 0; }

        /** Get, or return defVal if absent. */
        public double get(int key, double defVal) {
            if (key == FREE) return defVal;
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) return defVal;
                if (k == key) return values[i];
                i = (i + 1) & mask;
            }
        }

        /** Accumulate delta into key (creates a new entry = delta if absent). */
        public void addTo(int key, double delta) {
            if (key == FREE) throw new IllegalArgumentException("Reserved key Integer.MIN_VALUE");
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) {
                    keys[i] = key;
                    values[i] = delta;
                    size++;
                    if (size > threshold) resize();
                    return;
                }
                if (k == key) {
                    values[i] += delta;
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        public void put(int key, double value) {
            if (key == FREE) throw new IllegalArgumentException("Reserved key Integer.MIN_VALUE");
            int i = hash(key) & mask;
            while (true) {
                int k = keys[i];
                if (k == FREE) {
                    keys[i] = key;
                    values[i] = value;
                    size++;
                    if (size > threshold) resize();
                    return;
                }
                if (k == key) {
                    values[i] = value;
                    return;
                }
                i = (i + 1) & mask;
            }
        }

        public void clear() {
            Arrays.fill(keys, FREE);
            Arrays.fill(values, 0);
            size = 0;
        }

        /**
         * Dump all entries into a double[] array indexed by key.
         * Used by materializeEucsRow -> O(1) lookup without boxing.
         * Slots outside size are left as-is (the caller is responsible for
         * resetting the destination beforehand).
         */
        public void materializeInto(double[] dest) {
            for (int i = 0; i < keys.length; i++) {
                int k = keys[i];
                if (k != FREE && k >= 0 && k < dest.length) {
                    dest[k] = values[i];
                }
            }
        }

        private void resize() {
            int oldCap = keys.length;
            int newCap = oldCap << 1;
            int[] oldK = keys;
            double[] oldV = values;
            keys = new int[newCap];
            values = new double[newCap];
            Arrays.fill(keys, FREE);
            mask = newCap - 1;
            threshold = (newCap * 3) >>> 2;
            size = 0;
            for (int i = 0; i < oldCap; i++) {
                int k = oldK[i];
                if (k != FREE) put(k, oldV[i]);
            }
        }
    }
}
