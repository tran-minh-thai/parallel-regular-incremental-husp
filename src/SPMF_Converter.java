import java.io.*;
import java.util.*;

/**
 * =====================================================================================
 * SPMF_Converter — Convert datasets from SPMF format to QSDB format
 * =====================================================================================
 * PURPOSE:
 *   Converts sequential sequence files from SPMF format (item IDs only) into QSDB
 *   format (with quantity + external utility), ready for experimentation.
 *
 * INPUT FORMAT (SPMF):
 *   Each line: "item1 item2 -1 item3 -1 -2"
 *   - Non-negative integers: item IDs
 *   - "-1": itemset separator
 *   - "-2": sequence terminator
 *
 * OUTPUT FORMAT:
 *   1. File _seq.txt (QSDB): "item1[q1] item2[q2] -1 item3[q3] -1 -2"
 *      - quantity drawn from Uniform[1, 10] with a weighted distribution
 *   2. File _eui.txt (External Utility): "itemID:profit"
 *      - profit drawn from Log-Normal(mu=2.5, sigma=1.0), clamped to [1, 1000]
 *
 * Reproducibility: random seed fixed at 42 so the same SPMF input always produces
 * the same QSDB output across runs.
 * =====================================================================================
 */
public class SPMF_Converter {

    // Fixed seed = 42 for reproducible output
    private static final Random random = new Random(42);

    private static final String OUTPUT_DIR = "datasets";
    private static final String SOURCE_DIR = "datasets";

    public static void main(String[] args) {
        String[] inputFiles = {
                "BIBLE.txt",
                "LEVIATHAN.txt",
                "SIGN.txt",
                "CHAINSTORE.txt",
                "FOODMART.txt"
        };

        File dir = new File(OUTPUT_DIR);
        if (!dir.exists()) dir.mkdirs();

        System.out.println("========== BATCH CONVERSION START ==========");
        System.out.println("[*] Random seed = 42 (reproducible output)");

        for (String fileName : inputFiles) {
            String fullInputPath = SOURCE_DIR.isEmpty()
                    ? fileName
                    : SOURCE_DIR + File.separator + fileName;
            processSingleFile(fullInputPath, fileName);
        }

        System.out.println("==================================================");
    }

    /**
     * Convert one SPMF file into two QSDB files (_seq.txt + _eui.txt).
     *
     * @param fullPath     absolute path to the SPMF source file
     * @param originalName original file name (used to derive output file names)
     */
    private static void processSingleFile(String fullPath, String originalName) {
        File inputFile = new File(fullPath);
        if (!inputFile.exists()) {
            System.err.println("[!] ERROR: file not found: " + inputFile.getAbsolutePath());
            return;
        }

        String baseName = originalName.replace(".txt", "");
        String seqOut = OUTPUT_DIR + File.separator + baseName + "_seq.txt";
        String euiOut = OUTPUT_DIR + File.separator + baseName + "_eui.txt";

        Set<Integer> itemRegistry = new TreeSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(inputFile));
             BufferedWriter bw = new BufferedWriter(new FileWriter(seqOut))) {

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) continue;

                String[] tokens = line.trim().split("\\s+");
                for (String t : tokens) {
                    try {
                        int id = Integer.parseInt(t);
                        if (id >= 0) {
                            // Weighted quantity: 70% -> [1,2], 20% -> [3,5], 10% -> [6,10]
                            int r = random.nextInt(100);
                            int q;
                            if (r < 70) q = random.nextInt(2) + 1;
                            else if (r < 90) q = random.nextInt(3) + 3;
                            else q = random.nextInt(5) + 6;

                            bw.write(id + "[" + q + "] ");
                            itemRegistry.add(id);
                        } else {
                            // Separator: -1 (itemset) or -2 (sequence end)
                            bw.write(id + " ");
                        }
                    } catch (NumberFormatException e) {
                        // skip non-numeric tokens
                    }
                }
                bw.write("\n");
            }

            generateEUI(euiOut, itemRegistry);
            System.out.println("[OK] Converted: " + originalName + " (" + itemRegistry.size() + " items)");

        } catch (IOException e) {
            System.err.println("[ERROR] " + originalName + ": " + e.getMessage());
        }
    }

    /**
     * Generate the External Utility Information (EUI) file for all items.
     * Profit distribution: Log-Normal(mu=2.5, sigma=1.0), clamped to [1, 1000].
     * Separator ":" matches the format of example_eui.txt.
     *
     * @param outputPath path of the EUI output file
     * @param items      sorted set of unique item IDs
     */
    private static void generateEUI(String outputPath, Set<Integer> items) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("# ItemID:Profit (Log-Normal Distribution seed=42)\n");
            for (int id : items) {
                double logNormal = Math.exp(random.nextGaussian() * 1.0 + 2.5);
                long profit = Math.round(Math.max(1, Math.min(1000, logNormal)));
                writer.write(id + ":" + profit + "\n");
            }
        }
    }
}
