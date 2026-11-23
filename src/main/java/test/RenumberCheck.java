package test;

import algorithms.QSeqDatabase;
import algorithms.SeqConverter;

import java.util.*;

/**
 * Check of the load-time item renumbering ({@link ExpUtil#renumberItems}): mining the renumbered database must give the
 * same patterns, with the same values, as mining the file's own ids.
 *
 *   java -cp out test.RenumberCheck <seq> <eui> <delta> <absB> <threads> [fault]
 *
 * The file is loaded twice, once with the ids it carries and once renumbered. The expected inverse map is rebuilt
 * from the definition (the i-th smallest distinct id of the raw load), not taken from the renumbering code. Both
 * databases are mined by the proposed method on the same four uniform batches; every renumbered pattern is translated
 * back and the two maps are compared key by key and value by value. A fault argument must make the check fail:
 * "collide" maps the two smallest ids to one item, "notranslate" compares the renumbered ids without mapping them back.
 * Prints the number of patterns compared; zero patterns is reported as unverified. Exit 0 only when equal.
 */
public class RenumberCheck {
    public static void main(String[] a) throws Exception {
        String seq = a[0], eui = a[1], fault = a.length > 5 ? a[5] : "";
        double delta = Double.parseDouble(a[2]);
        int absB = Integer.parseInt(a[3]), threads = Integer.parseInt(a[4]);

        QSeqDatabase db = new QSeqDatabase();
        db.loadExternalUtility(eui);
        List<List<int[]>> raw = db.readSequences(seq);
        TreeSet<Integer> ids = new TreeSet<>();
        for (List<int[]> s : raw) for (int[] e : s) for (int k = 0; k < e.length; k += 2) ids.add(e[k]);
        int[] inverse = new int[ids.size() + 1];
        int i = 0;
        for (int id : ids) inverse[++i] = id;

        List<List<int[]>> ren = new QSeqDatabase() {{ loadExternalUtility(eui); }}.readSequences(seq);
        int[] st = ExpUtil.renumberItems(ren);
        if (fault.equals("collide"))
            for (List<int[]> s : ren) for (int[] e : s) for (int k = 0; k < e.length; k += 2) if (e[k] == 2) e[k] = 1;

        ExpConfig.absoluteMode = true;
        ExpConfig.absoluteB = absB;
        Map<String, long[]> want = mine(raw, delta, threads);
        Map<String, long[]> got = mine(ren, delta, threads);
        Map<String, long[]> back = new HashMap<>();
        for (Map.Entry<String, long[]> en : got.entrySet()) back.put(translate(en.getKey(), inverse, fault), en.getValue());

        int same = 0, diff = 0;
        for (Map.Entry<String, long[]> en : want.entrySet()) {
            long[] b = back.get(en.getKey());
            if (b != null && Arrays.equals(b, en.getValue())) same++; else diff++;
        }
        int extra = 0;
        for (String k : back.keySet()) if (!want.containsKey(k)) extra++;
        boolean renumbered = st[2] == 1;
        System.out.printf("renumber check %s: %d distinct items, largest id %d, %s; patterns %d (file ids) vs %d (renumbered);"
                        + " equal %d, differing %d, extra %d%n", ExpUtil.datasetTag(seq), st[0], st[1],
                renumbered ? "renumbered" : "already 1..n, kept", want.size(), got.size(), same, diff, extra);
        boolean ok = !want.isEmpty() && diff == 0 && extra == 0 && want.size() == got.size();
        System.out.println(want.isEmpty() ? "UNVERIFIED: no pattern to compare" : (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }

    static Map<String, long[]> mine(List<List<int[]>> all, double delta, int threads) {
        Map<String, long[]> out = new HashMap<>();
        for (Map.Entry<String, long[]> en : ExpUtil.run(ExpConfig.newProposed(threads), ExpUtil.split(all, ExpConfig.SCEN_A),
                delta, 0.06).entrySet())
            out.put(SeqConverter.canonical(en.getKey()), en.getValue());
        return out;
    }

    /** Map a canonical key of the renumbered database back to file ids and re-canonicalize. */
    static String translate(String key, int[] inverse, String fault) {
        StringBuilder sb = new StringBuilder();
        StringBuilder num = new StringBuilder();
        for (char c : (key + " ").toCharArray()) {
            if (Character.isDigit(c)) { num.append(c); continue; }
            if (num.length() > 0) {
                int v = Integer.parseInt(num.toString());
                sb.append(fault.equals("notranslate") ? v : (v >= 1 && v < inverse.length ? inverse[v] : -1));
                num.setLength(0);
            }
            sb.append(c);
        }
        return SeqConverter.canonical(sb.toString().trim());
    }
}
