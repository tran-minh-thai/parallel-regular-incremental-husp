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
         s.add(new DatasetSpec("SIGN",       0.030,  0.30, ExpConfig.SCEN_A, true));   // 730 DENSE → S1-ONLY: S4 "B-Increasing" (10% first batch) collapses the seeding threshold → combinatorial SHS blow-up → OOM even at 32g (T-independent). S1/A-Uniform is fine (200MB, HS=29). Skip S2/S4 like FIFA/KOSARAK.
         s.add(new DatasetSpec("LEVIATHAN",  0.030,  0.30, ExpConfig.SCEN_A));   // δ RAISED 0.005→0.03 (2026-07-11): 0.005 OOM'd even @24g on M5 (oracle=137312, adaptive SHS buffer explodes). 0.03 MEASURED = 119MB, HS=1024. Lower later only if a bigger machine allows.
         s.add(new DatasetSpec("BIBLE",      0.050,  0.30, ExpConfig.SCEN_A));   // δ RAISED 0.0025→0.05 (2026-07-11): 0.0025 OOM'd @16–24g on M5. 0.05 = CONSERVATIVE safe value (sparse + high threshold → small space). After a successful run, read BIBLE peak_mb; if there is headroom, lower δ for more patterns.
        // ---- Replace Chainstore/Foodmart (corrupt files) with FIFA + KOSARAK ----
        // COST WARNING: FIFA is DENSE + has LONG sequences (≤100 events) → SLOW (δ=0.05: ~7 min/run, HS=29).
        // KOSARAK has 990K sequences → needs -Xmx≥16g, VERY heavy. Consider raising δ or using only for scalability.
         s.add(new DatasetSpec("FIFA",       0.050,  0.30, ExpConfig.SCEN_A, true));   // 20,450 DENSE — ~7min/run,690MB; S1 ONLY
         s.add(new DatasetSpec("KOSARAK",    0.050,  0.30, ExpConfig.SCEN_A, true));   // 990,002 — 58s/4.2GB; S1 ONLY; -Xmx16g+
        return DatasetSpec.onlyAvailable(s);
    }
}
