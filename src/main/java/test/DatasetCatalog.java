package test;

import java.util.ArrayList;
import java.util.List;

/**
 * Dataset catalog + parameters for the PARALLEL experiment. EDIT HERE to enable/disable a dataset
 * or change parameters — DO NOT modify the runner class. Each dataset is ONE {@code list.add(...)}
 * line; prefix with {@code //} to DISABLE.
 * <p>
 * The δ, ρ parameters reference <b>Table 10</b> of the Adaptive-RIncHusp paper (reference only; the
 * sequential design is not carried over verbatim — the present study is parallel). δ is written as
 * a RATIO (=%/100):
 * <ul>
 *   <li>{@code minUtilRatio} (δ) — minUtil = δ × totalDbUtility.</li>
 *   <li>{@code maxRegRatio}  (ρ) — maxReg  = ρ × numSequences.</li>
 * </ul>
 * Batch splitting is NO LONGER fixed in the spec — the runner iterates over 4 distribution
 * SCENARIOS ({@link ExpConfig#SCENARIOS}); {@code batchRatios} in the spec is only a default when
 * needed.
 */
public final class DatasetCatalog {
    private DatasetCatalog() {}

    /** TEST suite — SMALL datasets that run INSTANTLY (with correct RHusp oracle for comparison). */
    public static List<DatasetSpec> testSuite() {
        List<DatasetSpec> s = new ArrayList<>();
        s.add(new DatasetSpec("example",  0.10, 0.60, ExpConfig.SCEN_A));   // running example (9 sequences)
        s.add(new DatasetSpec("example2", 0.10, 0.60, ExpConfig.SCEN_A));   // second test (12 short sequences)
        return DatasetSpec.onlyAvailable(s);
    }

    /**
     * OFFICIAL suite — PARALLEL benchmark. 5 datasets per Table 10 (δ references the paper).
     * ENABLE/DISABLE with {@code //}. Each dataset runs over 4 batch distribution scenarios (A/B/C/D)
     * in the runner.
     * <p>Available: BIBLE, LEVIATHAN, SIGN. MISSING (files to be added): Chainstore, Foodmart.
     */
    public static List<DatasetSpec> officialSuite() {
        List<DatasetSpec> s = new ArrayList<>();
        // δ = FEASIBLE THRESHOLD measured empirically (PerfProbe, -Xmx8g, T=4) — tune per dataset
        // DENSITY, do NOT hard-copy the paper's δ: a DENSE dataset (SIGN) has an exploding pattern
        // space at low δ, so δ must be raised.
        // (Right column: measured peak & #HS; raise -Xmx to lower δ further.)
         s.add(new DatasetSpec("SIGN",       0.030,  0.30, ExpConfig.SCEN_A));   // 730 DENSE. WAS S1-only: S4 "B-Increasing" (10% D_old) OOM'd even at 32g. CAUSE FOUND (2026-07-14): the sub-natural buffer θ₀=0.4·δ·U(D_old) — a 10% D_old drove it to 0.1·minUtil and the semi-high set exploded. With μ=1 (θ₀ = δ·U(D_old), the NATURAL threshold) all 4 distributions run: peak 12–92 MB, recall 1.0000. Full suite restored.
         s.add(new DatasetSpec("LEVIATHAN",  0.020,  0.30, ExpConfig.SCEN_A));   // δ 0.03→0.02 (2026-07-14). The old 0.005 blow-up was the sub-natural buffer, not the data. Measured with μ=1: 0.03→1036 pat/121MB, 0.02→3320 pat/214MB/3.0s, 0.01→22417 pat/1832MB/13.3s (too heavy for S8's 64-batch Par-Remine). 0.02 = the richest δ that stays light. δ=0.005 STILL thrashes into swap — do not probe it again.
         s.add(new DatasetSpec("BIBLE",      0.010,  0.30, ExpConfig.SCEN_A));   // δ LOWERED 0.05→0.01 (2026-07-14). The 0.05 was a workaround for an OOM caused by the sub-natural buffer (θ₀=0.4·δ·U(D_old)), not by the data; at δ=0.05 the oracle held only FIVE patterns — far too thin for a results table. With μ=1 measured at δ=0.01: oracle=1349, 2500 ms, peak 263 MB, recall 1.0000. (0.05→5, 0.03→58, 0.02→201, 0.01→1349 patterns.)
        // ---- Replace Chainstore/Foodmart (corrupt files) with FIFA + KOSARAK ----
        // COST WARNING: FIFA is DENSE + has LONG sequences (≤100 events) → SLOW (δ=0.05: ~7 min/run, HS=29).
        // KOSARAK has 990K sequences → needs -Xmx≥16g, VERY heavy. Consider raising δ or using only for scalability.
         s.add(new DatasetSpec("FIFA",       0.050,  0.30, ExpConfig.SCEN_A, true));   // 20,450 DENSE — ~7min/run,690MB; S1 ONLY
         s.add(new DatasetSpec("KOSARAK",    0.050,  0.30, ExpConfig.SCEN_A, true));   // 990,002 — 58s/4.2GB; S1 ONLY; -Xmx16g+
        return DatasetSpec.onlyAvailable(s);
    }
}
