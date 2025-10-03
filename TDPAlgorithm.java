package demo.gzoltar;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import demo.gzoltar.TDPDataStructures.*;

/**
 * Main TDP Algorithm Controller
 * Orchestrates the Test, Diagnose, and Plan loop using GZoltar data
 */
public class TDPAlgorithm {
    
    private final FilteredHittingSetComputer  hittingSetComputer;
    private final EntropyTestPlanner testPlanner;
    
    // TDP state
    private List<String> elements;
    private List<EmbeddedSFL.TestCase> observedTests;
    private boolean[][] coverageMatrix;
    private List<EmbeddedSFL.ElemStats> elementStats;
    private List<AvailableTest> availableTests;
    /**
    * Build available tests from GZoltar data
    * This reads ALL tests from the test suite, not just executed ones
    */
    private List<AvailableTest> buildAvailableTestsFromGZoltar() throws IOException {
        Path testsFile = Paths.get(".gzoltar/sfl/txt/tests.csv");
        Path spectraFile = Paths.get(".gzoltar/sfl/txt/spectra.csv");
        Path matrixFile = Paths.get(".gzoltar/sfl/txt/matrix.txt");
        
        // Load all test names
        List<String> allTestNames = loadAllTestNames(testsFile);
        
        // Load matrix to get traces
        boolean[][] fullMatrix = EmbeddedSFL.parseMatrix(matrixFile, 
                                                        allTestNames.size(), 
                                                        elements.size());
        
        // Build available tests (excluding already observed ones)
        List<AvailableTest> available = new ArrayList<>();
        Set<String> observedTestNames = observedTests.stream()
                                                    .map(t -> t.name)
                                                    .collect(Collectors.toSet());
        
        for (int i = 0; i < allTestNames.size(); i++) {
            String testName = allTestNames.get(i);
            
            // Skip tests we've already run
            if (observedTestNames.contains(testName)) {
                continue;
            }
            
            // Extract trace for this test from matrix
            Set<String> trace = new HashSet<>();
            for (int j = 0; j < elements.size() && j < fullMatrix[i].length; j++) {
                if (fullMatrix[i][j]) {
                    trace.add(elements.get(j));
                }
            }
            
            // Only add if trace is non-empty
            if (!trace.isEmpty()) {
                available.add(new AvailableTest(testName, trace));
            }
        }
        
        System.out.printf("Built %d available tests from test suite%n", available.size());
        return available;
    }

    /**
     * Load all test names from tests.csv (both executed and not-yet-executed)
     */
    private List<String> loadAllTestNames(Path testsFile) throws IOException {
        List<String> testNames = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(testsFile)) {
            String line;
            boolean headerSkipped = false;
            
            while ((line = br.readLine()) != null) {
                if (!headerSkipped && line.toLowerCase().contains("name")) {
                    headerSkipped = true;
                    continue;
                }
                
                String[] parts = line.split(",");
                if (parts.length > 0) {
                    testNames.add(parts[0].trim());
                }
            }
        }
        return testNames;
    }
    
    public TDPAlgorithm() {
        this.hittingSetComputer = new FilteredHittingSetComputer ();
        this.testPlanner = new EntropyTestPlanner();
    }
    
    /**
     * Main entry point: Run the complete TDP algorithm
     * @param gzoltarDataDir Directory containing GZoltar output files
     * @return Final diagnosis result
     */
    public Diagnosis runTDP(Path gzoltarDataDir) throws Exception {
        System.out.println("ðŸš€ Starting TDP (Test, Diagnose, Plan) Algorithm");
        System.out.println("===================================================================");
        
        // Phase 1: Initialize from GZoltar data
        initializeFromGZoltarData(gzoltarDataDir);
        
        // Phase 2: Run TDP loop
        return executeTDPLoop();
    }
    
    /**
     * Initialize TDP state from GZoltar data
     * Allows selecting which tests to use as initial observations
     */
    private void initializeFromGZoltarData(Path gzoltarDataDir) throws Exception {
        System.out.println("📊 Phase 1: Loading GZoltar Data");
        System.out.println("------------------------------------------------");
        
        // Load GZoltar files
        Path spectra = gzoltarDataDir.resolve("spectra.csv");
        Path tests = gzoltarDataDir.resolve("tests.csv");
        Path matrix = gzoltarDataDir.resolve("matrix.txt");
        
        if (!Files.isRegularFile(spectra) || !Files.isRegularFile(tests) || !Files.isRegularFile(matrix)) {
            throw new FileNotFoundException("Expected files not found in " + gzoltarDataDir.toAbsolutePath());
        }
        
        // Load ALL tests and their traces
        elements = EmbeddedSFL.readNonEmptyLines(spectra);
        if (!elements.isEmpty() && elements.get(0).toLowerCase().contains("name")) {
            elements = elements.subList(1, elements.size());
        }
        
        List<EmbeddedSFL.TestCase> allTests = EmbeddedSFL.parseTests(tests);
        boolean[][] allCoverage = EmbeddedSFL.parseMatrix(matrix, allTests.size(), elements.size());
        
        System.out.printf("Loaded %d tests with full coverage data%n", allTests.size());
        
        // *** NEW: Let user select which tests to use as initial observations ***
        observedTests = selectInitialObservations(allTests);
        
        // Build coverage matrix for observed tests only
        coverageMatrix = new boolean[observedTests.size()][elements.size()];
        for (int i = 0; i < observedTests.size(); i++) {
            String testName = observedTests.get(i).name;
            
            // Find this test in the full test list
            for (int j = 0; j < allTests.size(); j++) {
                if (allTests.get(j).name.equals(testName)) {
                    // Copy its coverage row
                    System.arraycopy(allCoverage[j], 0, coverageMatrix[i], 0, elements.size());
                    break;
                }
            }
        }
        
        // Build element statistics
        elementStats = buildElementStatistics();
        testPlanner.setComponentStats(elementStats);
        
        // Create available tests (all tests EXCEPT observed ones)
        availableTests = buildAvailableTestsFromAllTests(allTests, allCoverage);
        
        // Print initial state
        System.out.printf("✅ Loaded %d elements%n", elements.size());
        System.out.printf("✅ Using %d tests as initial observations%n", observedTests.size());
        System.out.printf("✅ Found %d failed tests%n", 
            observedTests.stream().filter(t -> t.failed).count());
        System.out.printf("✅ Created %d available tests for planning%n", availableTests.size());
        
        System.out.println("\nInitial Test Results:");
        for (EmbeddedSFL.TestCase test : observedTests) {
            System.out.printf("  %s: %s%n", test.name, test.failed ? "FAILED ❌" : "PASSED ✅");
        }
    }

    /**
     * Let user select which tests to use as initial observations
     */
    private List<EmbeddedSFL.TestCase> selectInitialObservations(List<EmbeddedSFL.TestCase> allTests) {
        System.out.println("\n=== SELECT INITIAL OBSERVATIONS ===");
        System.out.println("Available tests:");
        
        for (int i = 0; i < allTests.size(); i++) {
            EmbeddedSFL.TestCase test = allTests.get(i);
            System.out.printf("%2d. %s [%s]%n", 
                i + 1, 
                test.name, 
                test.failed ? "FAILED" : "PASSED");
        }
        
        System.out.println("\nEnter test numbers to use as initial observations (comma-separated):");
        System.out.println("Example: 1,5,7,10  (or press Enter to use all)");
        System.out.print("> ");
        
        Scanner scanner = new Scanner(System.in);
        String input = scanner.nextLine().trim();
        
        if (input.isEmpty()) {
            System.out.println("Using all tests as observations");
            return new ArrayList<>(allTests);
        }
        
        List<EmbeddedSFL.TestCase> selected = new ArrayList<>();
        String[] indices = input.split(",");
        
        for (String indexStr : indices) {
            try {
                int index = Integer.parseInt(indexStr.trim()) - 1;
                if (index >= 0 && index < allTests.size()) {
                    selected.add(allTests.get(index));
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid number: " + indexStr);
            }
        }
        
        System.out.printf("Selected %d tests as initial observations%n", selected.size());
        return selected;
    }

    /**
     * Build available tests from ALL tests (excluding observed ones)
     */
    private List<AvailableTest> buildAvailableTestsFromAllTests(
            List<EmbeddedSFL.TestCase> allTests, 
            boolean[][] allCoverage) {
        
        Set<String> observedTestNames = observedTests.stream()
            .map(t -> t.name)
            .collect(Collectors.toSet());
        
        List<AvailableTest> available = new ArrayList<>();
        
        for (int i = 0; i < allTests.size(); i++) {
            EmbeddedSFL.TestCase test = allTests.get(i);
            
            // Skip if already observed
            if (observedTestNames.contains(test.name)) {
                continue;
            }
            
            // Extract trace from coverage matrix
            Set<String> trace = new HashSet<>();
            for (int j = 0; j < elements.size() && j < allCoverage[i].length; j++) {
                if (allCoverage[i][j]) {
                    trace.add(elements.get(j));
                }
            }
            
            if (!trace.isEmpty()) {
                available.add(new AvailableTest(test.name, trace));
            }
        }
        
        return available;
    }
    
    /**
     * Execute the main TDP loop: Diagnose â†’ Plan â†’ Test â†’ Repeat
     */
    private Diagnosis executeTDPLoop() {
        System.out.println("\nðŸ”„ Phase 2: TDP Iterative Loop");
        System.out.println("-----------------------------------------------");
        
        int iteration = 1;
        final int MAX_ITERATIONS = 10;
        
        while (iteration <= MAX_ITERATIONS) {
            System.out.printf("%nðŸ” === TDP Iteration %d ===%n", iteration);
            
            // Step 1: DIAGNOSE - Generate current diagnosis set
            List<Diagnosis> diagnoses = generateCurrentDiagnoses();
            
            if (diagnoses.isEmpty()) {
                System.out.println("âŒ No diagnoses generated - algorithm failed");
                return null;
            }
            
            // Display current diagnoses
            displayDiagnoses(diagnoses);
            
            // Step 2: Check stopping condition
            DiagnosisStatistics stats = testPlanner.getDiagnosisStatistics(diagnoses);
            System.out.println("\nðŸ“Š Diagnosis Statistics:");
            System.out.println("  " + stats);
            
            if (stats.isComplete()) {
                System.out.println("\nðŸŽ¯ TDP COMPLETE! Final diagnosis found:");
                Diagnosis finalDiagnosis = stats.getMostLikelyDiagnosis();
                displayFinalResult(finalDiagnosis);
                return finalDiagnosis;
            }
            
            // Step 3: PLAN - Select next test
            AvailableTest nextTest = testPlanner.selectBestTest(availableTests, diagnoses);
            
            if (nextTest == null) {
                System.out.println("âŒ No suitable test found for planning - ending TDP");
                break;
            }
            
            // Step 4: TEST - Execute the selected test
            TestResult testResult = executeTest(nextTest);
            
            // Step 5: Update state with new test result
            updateStateWithNewTest(testResult);
            
            iteration++;
        }
        
        // If we exit the loop without finding a single diagnosis
        List<Diagnosis> finalDiagnoses = generateCurrentDiagnoses();
        if (!finalDiagnoses.isEmpty()) {
            Diagnosis bestDiagnosis = finalDiagnoses.get(0); // Already sorted by probability
            System.out.println("\nâš ï¸  TDP ended without single diagnosis. Best diagnosis:");
            displayFinalResult(bestDiagnosis);
            return bestDiagnosis;
        }
        
        System.out.println("âŒ TDP failed to find any diagnosis");
        return null;
    }
    
    /**
     * Generate current diagnoses from observed test failures
     */
    private List<Diagnosis> generateCurrentDiagnoses() {
        // Extract conflicts from failed tests
        List<Conflict> conflicts = FilteredHittingSetComputer.extractConflicts(
            observedTests, coverageMatrix, elements);
        
        if (conflicts.isEmpty()) {
            System.out.println("No conflicts found - no failed tests");
            return new ArrayList<>();
        }
        
        // Compute diagnoses using hitting sets
        return hittingSetComputer.computeDiagnoses(conflicts, elementStats, 20);
    }
    
    /**
     * Display current diagnoses in a readable format
     */
    private void displayDiagnoses(List<Diagnosis> diagnoses) {
        System.out.printf("%nðŸ“‹ Current Diagnoses (%d total):%n", diagnoses.size());
        
        for (int i = 0; i < Math.min(diagnoses.size(), 10); i++) {
            Diagnosis d = diagnoses.get(i);
            System.out.printf("  %d. [%.3f] %s%n", 
                i + 1, d.getProbability(), formatComponents(d.getComponents()));
        }
        
        if (diagnoses.size() > 10) {
            System.out.printf("  ... and %d more diagnoses%n", diagnoses.size() - 10);
        }
    }
    
    /**
     * Execute a test (simulated - in practice would run actual test)
     */
    // In TDPAlgorithm.java - update this method:
    private TestResult executeTest(AvailableTest test) {
        System.out.printf("%n🧪 === EXECUTING TEST: %s ===%n", test.getTestName());
        System.out.println("📍 Expected to cover: " + formatComponents(test.getEstimatedTrace()));
        
        // In simulation mode, ask user for result
        // In real mode, you would actually run the test here
        Scanner scanner = new Scanner(System.in);
        System.out.print("Did this test PASS or FAIL? (p/f): ");
        String input = scanner.nextLine().trim().toLowerCase();
        boolean passed = input.startsWith("p");
        
        System.out.printf("Test result: %s%n", passed ? "PASSED ✅" : "FAILED ❌");
        
        // Use the estimated trace as actual trace
        // In a real implementation, you'd get this from actual execution
        return new TestResult(test.getTestName(), passed, test.getEstimatedTrace());
    }
    
    /**
     * Update TDP state with results from newly executed test
     */
    private void updateStateWithNewTest(TestResult testResult) {
        System.out.printf("%nðŸ”„ Updating state with test result: %s = %s%n", 
            testResult.getTestName(), testResult.isPassed() ? "PASSED âœ…" : "FAILED âŒ");
        
        // Add new test to observed tests
        EmbeddedSFL.TestCase newTestCase = new EmbeddedSFL.TestCase();
        newTestCase.name = testResult.getTestName();
        newTestCase.failed = testResult.isFailed();
        observedTests.add(newTestCase);
        
        // Expand coverage matrix
        coverageMatrix = expandCoverageMatrix(testResult);
        
        // Rebuild element statistics
        elementStats = buildElementStatistics();
        testPlanner.setComponentStats(elementStats);
        // Remove executed test from available tests
        availableTests.removeIf(t -> t.getTestName().equals(testResult.getTestName()));
        
        System.out.printf("âœ… Updated state: %d observed tests, %d available tests remaining%n",
            observedTests.size(), availableTests.size());
    }
    
    /**
     * Build element statistics for Barinel computation
     */
    private List<EmbeddedSFL.ElemStats> buildElementStatistics() {
        List<EmbeddedSFL.ElemStats> stats = new ArrayList<>();
        
        for (int j = 0; j < elements.size(); j++) {
            int ef = 0, ep = 0, nf = 0, np = 0;
            
            for (int i = 0; i < observedTests.size(); i++) {
                boolean covers = (j < coverageMatrix[i].length) && coverageMatrix[i][j];
                boolean failed = observedTests.get(i).failed;
                
                if (failed && covers) ef++;
                else if (!failed && covers) ep++;
                else if (failed && !covers) nf++;
                else np++;
            }
            
            EmbeddedSFL.ElemStats es = new EmbeddedSFL.ElemStats();
            es.id = elements.get(j);
            es.ef = ef;
            es.ep = ep;
            es.nf = nf;
            es.np = np;
            stats.add(es);
        }
        
        return stats;
    }
    
    /**
     * Expand coverage matrix to include new test result
     */
    private boolean[][] expandCoverageMatrix(TestResult newTest) {
        boolean[][] newMatrix = new boolean[observedTests.size()][elements.size()];
        
        // Copy existing coverage
        for (int i = 0; i < observedTests.size() - 1; i++) {
            System.arraycopy(coverageMatrix[i], 0, newMatrix[i], 0, 
                Math.min(coverageMatrix[i].length, elements.size()));
        }
        
        // Add new test coverage (last row)
        int newTestIndex = observedTests.size() - 1;
        for (int j = 0; j < elements.size(); j++) {
            String element = elements.get(j);
            newMatrix[newTestIndex][j] = newTest.getActualTrace().contains(element);
        }
        
        return newMatrix;
    }
    
    /**
     * Format component names for display
     */
    private String formatComponents(Set<String> components) {
        if (components.size() <= 3) {
            return components.toString();
        } else {
            List<String> sorted = components.stream().sorted().collect(Collectors.toList());
            return String.format("[%s, %s, ... +%d more]", 
                sorted.get(0), sorted.get(1), components.size() - 2);
        }
    }
    
    /**
     * Display final diagnosis result
     */
    private void displayFinalResult(Diagnosis finalDiagnosis) {
        System.out.println("ðŸŽ¯ FINAL DIAGNOSIS RESULT");
        System.out.println("===================================================================");
        System.out.printf("ðŸ”§ Faulty Components (Probability: %.1f%%):%n", 
            finalDiagnosis.getProbability() * 100);
        
        for (String component : finalDiagnosis.getComponents()) {
            System.out.println("  â€¢ " + component);
        }
        
        System.out.println("\nðŸ“Š Recommendation:");
        System.out.println("  Focus debugging efforts on the components listed above.");
        System.out.println("  These components are most likely responsible for the observed failures.");
    }
    
    /**
     * Convenience method to run TDP with default directory
     */
    public static void main(String[] args) throws Exception {
        Path gzoltarDir = Paths.get(args.length > 0 ? args[0] : ".gzoltar/sfl/txt");
        
        TDPAlgorithm tdp = new TDPAlgorithm();
        Diagnosis result = tdp.runTDP(gzoltarDir);
        
        if (result != null) {
            System.out.println("\nâœ… TDP completed successfully!");
        } else {
            System.out.println("\nâŒ TDP failed to find a diagnosis");
        }
    }
}