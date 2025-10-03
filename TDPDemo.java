package demo.gzoltar;

import java.nio.file.*;
import java.util.*;
import demo.gzoltar.TDPDataStructures.*;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Complete TDP demonstration
 * This shows how all components work together
 */
public class TDPDemo {
    
    public static void main(String[] args) throws Exception {
        System.out.println("TDP ALGORITHM DEMONSTRATION");
        System.out.println("====================================================================");
        System.out.println("This demo shows the complete Test, Diagnose, and Plan workflow.");
        System.out.println("Make sure you have run GZoltar on the Calculator tests first!\n");
        
        // Step 1: Check if GZoltar data exists
        Path gzoltarDir = Paths.get(".gzoltar/sfl/txt");
        if (!Files.exists(gzoltarDir)) {
            System.out.println("GZoltar data not found at: " + gzoltarDir.toAbsolutePath());
            System.out.println("Please run the following steps first:");
            System.out.println("1. Compile: mvn compile test-compile");
            System.out.println("2. Run GZoltar with VS Code extension or command line");
            System.out.println("3. Ensure failed tests exist in the test suite");
            return;
        }
        
        // Step 2: Verify GZoltar files
        if (!Files.exists(gzoltarDir.resolve("spectra.csv")) ||
            !Files.exists(gzoltarDir.resolve("tests.csv")) ||
            !Files.exists(gzoltarDir.resolve("matrix.txt"))) {
            
            System.out.println("Missing GZoltar output files in: " + gzoltarDir.toAbsolutePath());
            System.out.println("Expected files: spectra.csv, tests.csv, matrix.txt");
            return;
        }
        
        // Step 3: Discover available tests from compiled classes
        System.out.println("Step 1: Discovering Available Tests");
        System.out.println("-----------------------------------------------");
        discoverAndSaveAvailableTests();
        
        // Step 4: Run traditional GZoltar analysis first (for comparison)
        System.out.println("\nStep 2: Traditional GZoltar Analysis");
        System.out.println("-----------------------------------------------");
        runTraditionalGZoltarAnalysis(gzoltarDir);
        
        // Step 5: Run TDP algorithm
        System.out.println("\nStep 3: TDP Algorithm");
        System.out.println("-----------------------------------------------");
        runTDPAlgorithm(gzoltarDir);
        
        System.out.println("\nDemo completed!");
    }
    
    /**
     * Discover all runnable test methods and save to file
     */
    private static void discoverAndSaveAvailableTests() throws IOException {
        Path testClasses = Paths.get("target/test-classes");
        Set<String> runnableTests = discoverRunnableTests(testClasses);
        
        if (runnableTests.isEmpty()) {
            System.out.println("No test methods discovered. Make sure you've compiled tests.");
            return;
        }
        
        // Create directory for TDP data
        Path tdpData = Paths.get("tdp-data");
        Files.createDirectories(tdpData);
        
        // Write discovered tests to file
        Path candidatesFile = tdpData.resolve("available-tests.txt");
        Files.write(candidatesFile, 
                   runnableTests.stream().sorted().collect(Collectors.toList()));
        
        System.out.println("Discovered " + runnableTests.size() + " test methods");
        System.out.println("Written to: " + candidatesFile.toAbsolutePath());
        System.out.println("\nSample tests found:");
        runnableTests.stream()
                     .sorted()
                     .limit(5)
                     .forEach(t -> System.out.println("  • " + t));
        
        if (runnableTests.size() > 5) {
            System.out.println("  ... and " + (runnableTests.size() - 5) + " more");
        }
    }
    
    /**
     * Run your existing EmbeddedSFL analysis for comparison
     */
    private static void runTraditionalGZoltarAnalysis(Path gzoltarDir) throws Exception {
        System.out.println("Running traditional Barinel analysis...");
        
        // Use your existing EmbeddedSFL code to show traditional rankings
        String[] args = {gzoltarDir.toString()};
        EmbeddedSFL.main(args);
        
        System.out.println("\n💡 Note: Traditional approach gives you ranked suspicious elements,");
        System.out.println("   but you still need to manually debug to find the actual bugs.");
    }
    
    /**
     * Run the complete TDP algorithm
     */
    private static void runTDPAlgorithm(Path gzoltarDir) throws Exception {
        System.out.println("Running TDP algorithm with entropy-based test planning...");
        
        TDPAlgorithm tdp = new TDPAlgorithm();
        Diagnosis result = tdp.runTDP(gzoltarDir);
        
        if (result != null) {
            System.out.println("\n TDP SUCCESS!");
            
        } else {
            System.out.println("\nTDP did not converge to a single diagnosis");
        }
    }
    
    /**
     * Discover all runnable JUnit test methods from compiled test classes
     */
    private static Set<String> discoverRunnableTests(Path testClassesDir) {
        try {
            if (!Files.isDirectory(testClassesDir)) {
                System.out.println("test-classes directory not found: " + testClassesDir.toAbsolutePath());
                return Collections.emptySet();
            }

            // Set up classloader with both test and main classes
            List<URL> urls = new ArrayList<>();
            urls.add(testClassesDir.toUri().toURL());
            Path mainClasses = Paths.get("target", "classes");
            if (Files.isDirectory(mainClasses)) {
                urls.add(mainClasses.toUri().toURL());
            }

            try (URLClassLoader cl = new URLClassLoader(
                    urls.toArray(new URL[0]), 
                    TDPDemo.class.getClassLoader())) {
                
                // Find all .class files
                List<String> classNames = listClassNames(testClassesDir);
                Set<String> discoveredTests = new HashSet<>();
                
                // Inspect each class for test methods
                for (String className : classNames) {
                    try {
                        Class<?> testClass = Class.forName(className, false, cl);
                        
                        // Skip if doesn't look like a test class
                        if (!looksLikeTestClass(className, testClass)) {
                            continue;
                        }
                        
                        // Find @Test annotated methods
                        for (Method method : safeGetDeclaredMethods(testClass)) {
                            if (isJUnitTestMethod(method)) {
                                // Format: demo.gzoltar.CalculatorTest#testAddPositive
                                String testId = className + "#" + method.getName();
                                discoveredTests.add(testId);
                            }
                        }
                    } catch (ClassNotFoundException | LinkageError e) {
                        // Skip classes that can't be loaded
                        continue;
                    }
                }
                
                return discoveredTests;
            }
        } catch (IOException e) {
            System.err.println("Error discovering tests: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * List all .class file names in the directory
     */
    private static List<String> listClassNames(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.getFileName().toString().contains("$")) // Skip inner classes
                .map(root::relativize)
                .map(p -> p.toString()
                           .replace('/', '.')
                           .replace('\\', '.')
                           .replaceAll("\\.class$", ""))
                .collect(Collectors.toList());
        }
    }

    /**
     * Check if class looks like a test class
     */
    private static boolean looksLikeTestClass(String className, Class<?> cls) {
        // Check naming convention
        String simpleName = className.contains(".") 
            ? className.substring(className.lastIndexOf('.') + 1) 
            : className;
        
        if (simpleName.endsWith("Test") || simpleName.endsWith("Tests") || simpleName.endsWith("IT")) {
            return true;
        }
        
        // Check for test methods
        return hasAnyTestMethod(cls);
    }

    /**
     * Check if class has any test methods
     */
    private static boolean hasAnyTestMethod(Class<?> cls) {
        for (Method m : safeGetDeclaredMethods(cls)) {
            if (isJUnitTestMethod(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Safely get declared methods (handles class loading errors)
     */
    private static Method[] safeGetDeclaredMethods(Class<?> cls) {
        try {
            return cls.getDeclaredMethods();
        } catch (Throwable t) {
            return new Method[0];
        }
    }

    /**
     * Check if method is annotated with JUnit @Test
     */
    private static boolean isJUnitTestMethod(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            String annotationName = annotation.annotationType().getName();
            
            // JUnit 5
            if (annotationName.equals("org.junit.jupiter.api.Test")) {
                return true;
            }
            if (annotationName.equals("org.junit.jupiter.params.ParameterizedTest")) {
                return true;
            }
            
            // JUnit 4
            if (annotationName.equals("org.junit.Test")) {
                return true;
            }
        }
        return false;
    }
}