package test;

import algorithms.AlgoRIncHUSP;
import algorithms.SeqConverter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the one property the sequential baseline must have by construction: it generates no new
 * pattern after seeding and evaluates exactly, so its answer is a SUBSET of the oracle's -- it may
 * miss, it must never invent. Extras are an implementation defect, and recall cannot show them,
 * so this probe counts them directly.
 *
 * <pre>
 *   java -cp out test.BaselineProbe datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.005 0.03 21 0.4
 * </pre>
 * The fifth argument is the declared absolute bound (0 = relative), the sixth the buffer factor.
 */
public class BaselineProbe {

    public static void main(String[] args) throws Exception {
        String seqFile = args[0], euiFile = args[1];
        double delta = Double.parseDouble(args[2]), rho = Double.parseDouble(args[3]);
        int absB = Integer.parseInt(args[4]);
        double mu = Double.parseDouble(args[5]);

        List<List<int[]>> all = ExpUtil.loadAll(seqFile, euiFile);
        List<List<List<int[]>>> batches = ExpUtil.split(all, ExpConfig.SCEN_A);
        Set<String> oracle = absB > 0 ? ExpUtil.oracleCanon(all, delta, absB)
                                      : ExpUtil.oracleCanon(all, delta, rho);
        System.out.printf("%s  N=%d  B=%s  mu=%.1f  oracle=%d%n",
                ExpUtil.datasetTag(seqFile), all.size(), absB > 0 ? String.valueOf(absB) : "rel", mu,
                oracle.size());

        AlgoRIncHUSP m = new AlgoRIncHUSP();
        m.bufferFactor = mu;
        if (absB > 0) m.setAbsoluteMaxReg(absB);
        Map<String, long[]> res = ExpUtil.run(m, batches, delta, rho);

        TreeSet<String> extras = new TreeSet<>();
        int hits = 0;
        for (String k : res.keySet()) {
            if (oracle.contains(SeqConverter.canonical(k))) hits++;
            else extras.add(k);
        }
        System.out.printf("  HS=%d  hits=%d  EXTRAS=%d  recall=%.4f  precision=%.4f%n",
                res.size(), hits, extras.size(),
                (double) hits / oracle.size(), res.isEmpty() ? 1.0 : (double) hits / res.size());
        for (String k : extras) {
            if (extras.size() <= 8 || extras.headSet(k).size() < 8)
                System.out.println("    EXTRA " + k + "  util=" + res.get(k)[0] + "  per=" + res.get(k)[1]);
        }
        System.out.println(extras.isEmpty() ? "  SUBSET PROPERTY HOLDS" : "  SUBSET PROPERTY VIOLATED");
    }
}
