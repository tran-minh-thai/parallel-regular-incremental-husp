package algorithms;

import common.Pattern;
import common.Sequence;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Runner for the STATIC REFERENCE baselines (USpan, HUSP-ULL) — HUSP mining cores, NOT
 * incremental, NOT regularity-aware. Per the manuscript they are "mining-core reference
 * points with a different result set" -> run SEPARATELY over the whole {@code D_new},
 * reporting pattern count/runtime; NOT included in the coverage comparison against the
 * RHUSP oracle.
 */
public final class ReferenceMiners {
    private ReferenceMiners() {}

    /** Result of one reference baseline run. */
    public static final class Result {
        public final String name;
        public final int patternCount;
        public final long runtimeMs;
        public final java.util.Set<String> patterns;   // canonical keys (for matching if needed)
        Result(String name, int c, long ms, java.util.Set<String> p) {
            this.name = name; this.patternCount = c; this.runtimeMs = ms; this.patterns = p;
        }
    }

    /** USpan over the whole DB with absolute threshold minUtil = δ·UD. */
    public static Result runUSpan(List<Sequence> db, double minUtilRatio) {
        double minUtil = minUtilRatio * SeqConverter.totalUtility(db);
        USpanAlgorithm algo = new USpanAlgorithm(minUtil);
        long t0 = System.currentTimeMillis();
        algo.run(db);
        long ms = System.currentTimeMillis() - t0;
        return new Result("USpan", algo.largeMap.size(), ms, canonSet(algo.largeMap));
    }

    /** HUSP-ULL over the whole DB with absolute threshold minUtil = δ·UD. */
    public static Result runHUSPULL(List<Sequence> db, double minUtilRatio) {
        double minUtil = minUtilRatio * SeqConverter.totalUtility(db);
        HUSPULLAlgorithm algo = new HUSPULLAlgorithm(minUtil);
        long t0 = System.currentTimeMillis();
        algo.run(db);
        long ms = System.currentTimeMillis() - t0;
        return new Result("HUSP-ULL", algo.largeMap.size(), ms, canonSet(algo.largeMap));
    }

    private static java.util.Set<String> canonSet(Map<Pattern, Double> m) {
        TreeSet<String> s = new TreeSet<>();
        for (Pattern p : m.keySet()) s.add(SeqConverter.canonical(p));
        return s;
    }
}
