package test;
import algorithms.*;


import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Specification of one dataset in an experiment scenario: paths + suitable parameters (δ, ρ, batch
 * splitting). Used by both {@link ExperimentTest} and {@link ExperimentOfficial} to run
 * AUTOMATICALLY over a LIST of datasets without passing command-line arguments (runs directly from
 * IntelliJ).
 */
public final class DatasetSpec {
    public final String tag;          // display name
    public final String seqFile;      // _seq path (resolved)
    public final String euiFile;      // _eui path (resolved)
    public final double minUtilRatio;        // minUtil ratio suited to the dataset
    public final double maxRegRatio;          // maxReg ratio
    public final double[] batchRatios;// batch splitting (first batch = D_old)
    public final boolean available;   // whether the files exist
    public final boolean s1Only;      // HEAVY dataset → run S1 (scalability) ONLY, skip S2/S4

    /**
     * DECLARED absolute regularity bound for --absolute mode: a pattern is regular when it recurs at
     * least every {@code absB} sequences, at every point in time. This is a GIVEN number, part of the
     * problem statement like delta -- never derived from the database size, because deriving it from
     * the final size would smuggle back the very quantity the absolute formulation exists to remove.
     * Zero means the dataset has no declared bound and absolute mode falls back to skipping it.
     */
    public final int absB;

    public DatasetSpec(String tag, double minUtilRatio, double maxRegRatio, double[] batchRatios) {
        this(tag, minUtilRatio, maxRegRatio, batchRatios, false, 0);
    }

    /** Declared absolute bound, for the --absolute suite; see {@link #absB}. */
    public DatasetSpec(String tag, double minUtilRatio, double maxRegRatio, double[] batchRatios, int absB) {
        this(tag, minUtilRatio, maxRegRatio, batchRatios, false, absB);
    }

    /** {@code s1Only=true}: dataset too heavy (FIFA/KOSARAK) → measure S1 only to avoid an hours-long suite. */
    public DatasetSpec(String tag, double minUtilRatio, double maxRegRatio, double[] batchRatios, boolean s1Only) {
        this(tag, minUtilRatio, maxRegRatio, batchRatios, s1Only, 0);
    }

    public DatasetSpec(String tag, double minUtilRatio, double maxRegRatio, double[] batchRatios,
                       boolean s1Only, int absB) {
        this.tag = tag; this.minUtilRatio = minUtilRatio; this.maxRegRatio = maxRegRatio;
        this.batchRatios = batchRatios; this.s1Only = s1Only; this.absB = absB;
        String seq = resolve(tag + "_seq.txt");
        String eui = resolve(tag + "_eui.txt");
        this.seqFile = seq; this.euiFile = eui;
        this.available = seq != null && eui != null;
    }

    /** Locate the dataset file in common locations (run from project root or module root, IntelliJ/terminal). */
    private static String resolve(String name) {
        String[] bases = {
            "datasets/", "RegIncHUSPM_Parallel_Code/datasets/", "../datasets/",
            "./", System.getProperty("user.dir") + "/datasets/"
        };
        for (String b : bases) {
            File f = new File(b + name);
            if (f.exists()) return f.getPath();
        }
        return null;
    }

    /** Keep only specs whose files actually exist. */
    public static List<DatasetSpec> onlyAvailable(List<DatasetSpec> specs) {
        List<DatasetSpec> out = new ArrayList<>();
        for (DatasetSpec s : specs) if (s.available) out.add(s);
        return out;
    }
}
