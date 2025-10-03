package demo.gzoltar;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

// GZoltar formulas (1.7.x)
import com.gzoltar.sfl.formulas.ISFLFormula;
import com.gzoltar.sfl.formulas.Ochiai;
import com.gzoltar.sfl.formulas.Tarantula;
import com.gzoltar.sfl.formulas.Barinel;

/**
 * Reads the SFL txt files produced by the VS Code GZoltar extension
 * (typically in ".gzoltar/sfl/txt/") and recomputes suspiciousness
 * using Ochiai, Tarantula, and Barinel. Writes CSVs and prints top ranks.
 */
public class EmbeddedSFL {

    static class ElemStats {
        String id;           // element identifier from spectra.csv
        int ef, ep, nf, np;  // failing/passing x covered/not covered
    }
    static class TestCase { String name; boolean failed; }

    public static void main(String[] args) throws Exception {
        // 1) Locate the folder with spectra.csv, tests.csv, matrix.txt
        //    If you want to pass a different folder, run with: -Dexec.args="<path>"
        Path sflDir = Paths.get(args.length > 0 ? args[0] : ".gzoltar/sfl/txt");

        Path spectra = sflDir.resolve("spectra.csv");
        Path tests   = sflDir.resolve("tests.csv");
        Path matrix  = sflDir.resolve("matrix.txt");

        if (!Files.isRegularFile(spectra) || !Files.isRegularFile(tests) || !Files.isRegularFile(matrix)) {
            throw new FileNotFoundException("Expected files not found in " + sflDir.toAbsolutePath()
                    + " (need spectra.csv, tests.csv, matrix.txt)");
        }

        // 2) Load
        List<String> elements = readNonEmptyLines(spectra);
        if (!elements.isEmpty() && elements.get(0).toLowerCase().contains("name")) {
            elements = elements.subList(1, elements.size()); // drop header if present
        }
        List<TestCase> testCases = parseTests(tests);
        boolean[][] cov = parseMatrix(matrix, testCases.size(), elements.size());

        // 3) Build counts for each program element
        List<ElemStats> stats = new ArrayList<>();
        for (int j = 0; j < elements.size(); j++) {
            int ef=0, ep=0, nf=0, np=0;
            for (int i = 0; i < testCases.size(); i++) {
                boolean covers = cov[i][j];
                boolean failed = testCases.get(i).failed;
                if (failed && covers) ef++;
                else if (!failed && covers) ep++;
                else if (failed && !covers) nf++;
                else np++;
            }
            ElemStats es = new ElemStats();
            es.id = elements.get(j);
            es.ef = ef; es.ep = ep; es.nf = nf; es.np = np;
            stats.add(es);
        }

        // 4) Choose formulas
        Map<String, ISFLFormula> formulas = new LinkedHashMap<>();
        formulas.put("ochiai",    new Ochiai());
        formulas.put("tarantula", new Tarantula());
        formulas.put("barinel",   new Barinel());

        // 5) Output folder for our custom rankings
        Path outDir = Paths.get(".gzoltar/sfl/custom");
        Files.createDirectories(outDir);

        // 6) Compute + write CSV for each formula
        for (Map.Entry<String, ISFLFormula> e : formulas.entrySet()) {
            String name = e.getKey();
            ISFLFormula f = e.getValue();

            List<String> lines = new ArrayList<>();
            lines.add("score,element");
            stats.stream()
                 .map(es -> new Object[]{
                     es.id,
                     // IMPORTANT ordering: compute(n00, n01, n10, n11)
                     f.compute(es.np, es.nf, es.ep, es.ef)
                 })
                 .sorted((a,b) -> Double.compare((double)b[1], (double)a[1]))
                 .forEach(row -> lines.add(String.format(Locale.US, "%.6f,%s", (double)row[1], (String)row[0])));

            Path csv = outDir.resolve(name + ".ranking.csv");
            Files.write(csv, lines, java.nio.charset.StandardCharsets.UTF_8);

            System.out.println("\n=== " + name.toUpperCase() + " (top 15) ===");
            lines.stream().skip(1).limit(15).forEach(System.out::println);
            System.out.println("→ Full CSV: " + csv.toAbsolutePath());
        }

        System.out.println("\nDone. Compare with the extension’s .gzoltar/sfl/txt/ochiai.ranking.csv");
    }

    // ---------- helpers ----------
    static List<String> readNonEmptyLines(Path p) throws IOException {
        try (Stream<String> s = Files.lines(p)) {
            return s.map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
        }
    }

    static List<TestCase> parseTests(Path testsCsv) throws IOException {
        List<TestCase> list = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(testsCsv)) {
            String line; boolean headerSkipped = false;
            while ((line = br.readLine()) != null) {
                if (!headerSkipped && line.toLowerCase().contains("name")) { headerSkipped = true; continue; }
                String[] p = line.split(",");
                if (p.length == 0) continue;
                TestCase t = new TestCase();
                t.name = p[0].trim();
                String status = (p.length > 1 ? p[1] : "").trim().toLowerCase();
                t.failed = status.contains("fail");
                list.add(t);
            }
        }
        return list;
    }

    static boolean[][] parseMatrix(Path matrixTxt, int T, int E) throws IOException {
        List<String> lines = readNonEmptyLines(matrixTxt);
        boolean[][] cov = new boolean[T][E];
        for (int i = 0; i < T && i < lines.size(); i++) {
            String[] bits = lines.get(i).split("\\s+|,");
            for (int j = 0; j < E && j < bits.length; j++) {
                cov[i][j] = "1".equals(bits[j]);
            }
        }
        return cov;
    }
}
