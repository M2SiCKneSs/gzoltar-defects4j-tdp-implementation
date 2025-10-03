package demo.gzoltar;

import java.util.*;
import java.util.stream.Collectors;
import demo.gzoltar.TDPDataStructures.*;

/** 
 * Implements entropy-based test planning for TDP
 * Selects tests that maximize information gain to reduce diagnosis uncertainty
 * 
 * UPDATED: Now uses actual goodness values from Barinel statistics
 * as described in the TDP paper
 */
public class EntropyTestPlanner {
    
    // Store component statistics for goodness calculation
    private Map<String, EmbeddedSFL.ElemStats> componentStats;
    
    public EntropyTestPlanner() {
        this.componentStats = new HashMap<>();
    }
    
    /**
     * Initialize the planner with component statistics from GZoltar/Barinel
     * This must be called before using selectBestTest()
     * 
     * @param stats List of element statistics containing ef, ep, nf, np values
     */
    public void setComponentStats(List<EmbeddedSFL.ElemStats> stats) {
        this.componentStats.clear();
        for (EmbeddedSFL.ElemStats stat : stats) {
            this.componentStats.put(stat.id, stat);
        }
        System.out.println("Initialized EntropyTestPlanner with " + stats.size() + " component statistics");
    }
    
    /**
     * Calculate goodness for a component based on its test execution history
     * 
     * Goodness(C) = probability that a test covering component C will PASS
     * Formula: goodness(C) = ep / (ef + ep)
     * where:
     *   ep = number of passed tests that cover C
     *   ef = number of failed tests that cover C
     * 
     * @param component The component identifier
     * @return Goodness value between 0.01 and 0.99
     */
    private double calculateGoodness(String component) {
        EmbeddedSFL.ElemStats stats = componentStats.get(component);
        
        if (stats == null) {
            System.out.println("WARNING: No statistics found for component: " + component);
            return 0.5;  // Neutral default if no statistics available
        }
        
        int totalCovering = stats.ef + stats.ep;
        
        if (totalCovering == 0) {
            // Component never covered by any test
            return 0.5;  // Neutral default
        }
        
        // Goodness = passed tests / total tests that cover this component
        double goodness = (double) stats.ep / totalCovering;
        
        // Bound goodness to avoid extreme values (0.01 to 0.99)
        // This prevents numerical instability in probability calculations
        goodness = Math.max(0.01, Math.min(0.99, goodness));
        
        return goodness;
    }
    
    /**
     * Select the best test to run next based on information gain
     * @param availableTests Tests that could be executed
     * @param currentDiagnoses Current set of diagnoses with probabilities
     * @return The test that maximizes expected information gain
     */
    public AvailableTest selectBestTest(List<AvailableTest> availableTests, 
                                      List<Diagnosis> currentDiagnoses) {
        
        if (availableTests.isEmpty()) {
            System.out.println("No available tests to select from");
            return null;
        }
        
        if (currentDiagnoses.isEmpty() || currentDiagnoses.size() == 1) {
            System.out.println("No need to select test - diagnosis already determined");
            return null;
        }
        
        if (componentStats.isEmpty()) {
            System.out.println("WARNING: No component statistics loaded! Call setComponentStats() first.");
            System.out.println("Falling back to random test selection.");
            return availableTests.get(new Random().nextInt(availableTests.size()));
        }
        
        System.out.println("\n=== ENTROPY-BASED TEST SELECTION ===");
        System.out.println("Evaluating " + availableTests.size() + " available tests...");
        
        double currentEntropy = computeEntropy(currentDiagnoses);
        System.out.printf("Current entropy: %.4f%n", currentEntropy);
        
        AvailableTest bestTest = null;
        double maxInfoGain = -1.0;
        
        // Evaluate information gain for each available test
        for (AvailableTest test : availableTests) {
            double infoGain = computeInformationGain(test, currentDiagnoses, currentEntropy);
            
            System.out.printf("Test %-40s: Info Gain = %.4f%n", test.getTestName(), infoGain);
            
            if (infoGain > maxInfoGain) {
                maxInfoGain = infoGain;
                bestTest = test;
            }
        }
        
        if (bestTest != null) {
            System.out.printf("\n✓ Selected test: %s (Info Gain: %.4f)%n", 
                            bestTest.getTestName(), maxInfoGain);
        }
        
        return bestTest;
    }
    
    /**
     * Compute entropy of current diagnosis set
     * Entropy(Ω) = -Σ p(Δᵢ) × log(p(Δᵢ))
     * 
     * Low entropy = high certainty (one diagnosis dominates)
     * High entropy = high uncertainty (many equally likely diagnoses)
     */
    public double computeEntropy(List<Diagnosis> diagnoses) {
        double entropy = 0.0;
        
        for (Diagnosis d : diagnoses) {
            if (d.getProbability() > 0) {
                entropy -= d.getProbability() * Math.log(d.getProbability());
            }
        }
        
        return entropy;
    }
    
    /**
     * Compute information gain for a specific test
     * 
     * InfoGain(t) = Entropy(Ω) - [p(t,Ω) × Entropy(Ω⁺(t)) + (1-p(t,Ω)) × Entropy(Ω⁻(t))]
     * 
     * This represents the expected reduction in uncertainty after performing test t
     */
    public double computeInformationGain(AvailableTest test, 
                                       List<Diagnosis> currentDiagnoses, 
                                       double currentEntropy) {
        
        // Estimate probability that test will pass using goodness values
        double pPass = estimateTestPassProbability(test, currentDiagnoses);
        double pFail = 1.0 - pPass;
        
        // Compute expected diagnosis sets if test passes or fails
        List<Diagnosis> diagnosesIfPass = updateDiagnosesForTestOutcome(currentDiagnoses, test, true);
        List<Diagnosis> diagnosesIfFail = updateDiagnosesForTestOutcome(currentDiagnoses, test, false);
        
        // Compute entropies for each outcome
        double entropyIfPass = computeEntropy(diagnosesIfPass);
        double entropyIfFail = computeEntropy(diagnosesIfFail);
        
        // Information gain = current entropy - expected entropy after test
        double expectedEntropy = pPass * entropyIfPass + pFail * entropyIfFail;
        double infoGain = currentEntropy - expectedEntropy;
        
        return Math.max(0, infoGain); // Ensure non-negative
    }
    
    /**
     * Estimate probability that a test will pass given current diagnoses
     * 
     * IMPROVED VERSION: Uses actual goodness values from Barinel statistics
     * as described in the TDP paper (Zamir et al., 2014)
     * 
     * Formula from paper:
     * p(t, Ω) = Σ(ω∈Ω) p(ω) · ∏(C∈(ω∩trace(t))) goodness(C)
     * 
     * @param test The test to evaluate
     * @param diagnoses Current set of diagnoses with probabilities
     * @return Estimated probability that test will pass (0.05 to 0.95)
     */
    private double estimateTestPassProbability(AvailableTest test, List<Diagnosis> diagnoses) {
        double weightedPassProb = 0.0;
        
        for (Diagnosis diagnosis : diagnoses) {
            // Find intersection: components that are both in test trace AND in diagnosis
            Set<String> intersection = new HashSet<>(test.getEstimatedTrace());
            intersection.retainAll(diagnosis.getComponents());
            
            double passGivenDiagnosis;
            
            if (intersection.isEmpty()) {
                // Test doesn't cover ANY faulty components
                // → Very likely to pass (no faulty code executed)
                passGivenDiagnosis = 0.95;
            } else {
                // Test covers some faulty components
                // Apply product formula: ∏ goodness(C) for C in intersection
                // This is the key improvement from the paper!
                
                passGivenDiagnosis = 1.0;
                for (String component : intersection) {
                    double goodness = calculateGoodness(component);
                    passGivenDiagnosis *= goodness;
                }
                
                // Ensure reasonable bounds to avoid numerical issues
                passGivenDiagnosis = Math.max(0.05, Math.min(0.95, passGivenDiagnosis));
            }
            
            // Weight by diagnosis probability (from Barinel)
            // p(ω) · passGivenDiagnosis
            weightedPassProb += diagnosis.getProbability() * passGivenDiagnosis;
        }
        
        // Final bounds check
        return Math.max(0.05, Math.min(0.95, weightedPassProb));
    }
    
    /**
     * Update diagnosis probabilities based on a hypothetical test outcome
     * Uses Bayes' rule to update probabilities given test result
     * 
     * IMPROVED VERSION: Uses actual goodness values
     * 
     * This is used for simulating "what if" scenarios during information gain calculation
     * 
     * @param currentDiagnoses Current set of diagnoses
     * @param test The test being considered
     * @param testPassed Whether we're simulating a PASS or FAIL outcome
     * @return Updated diagnoses with new probabilities
     */
    private List<Diagnosis> updateDiagnosesForTestOutcome(List<Diagnosis> currentDiagnoses,
                                                        AvailableTest test,
                                                        boolean testPassed) {
        List<Diagnosis> updatedDiagnoses = new ArrayList<>();
        
        for (Diagnosis diagnosis : currentDiagnoses) {
            // Find intersection between test trace and faulty components
            Set<String> intersection = new HashSet<>(test.getEstimatedTrace());
            intersection.retainAll(diagnosis.getComponents());
            
            double likelihoodGivenDiagnosis;
            
            if (intersection.isEmpty()) {
                // Test doesn't cover any faulty components
                if (testPassed) {
                    // PASS is very likely - expected outcome
                    likelihoodGivenDiagnosis = 0.95;
                } else {
                    // FAIL is very unlikely - something else is wrong
                    likelihoodGivenDiagnosis = 0.05;
                }
            } else {
                // Test covers some faulty components
                // Use goodness product to calculate likelihood
                
                double goodnessProduct = 1.0;
                for (String component : intersection) {
                    goodnessProduct *= calculateGoodness(component);
                }
                
                if (testPassed) {
                    // PASS likelihood = product of goodness values
                    likelihoodGivenDiagnosis = goodnessProduct;
                } else {
                    // FAIL likelihood = 1 - goodness product
                    likelihoodGivenDiagnosis = 1.0 - goodnessProduct;
                }
                
                // Bound to avoid extreme values
                likelihoodGivenDiagnosis = Math.max(0.05, Math.min(0.95, likelihoodGivenDiagnosis));
            }
            
            // Bayes' rule: P(diagnosis | test outcome) ∝ P(test outcome | diagnosis) × P(diagnosis)
            double newProbability = diagnosis.getProbability() * likelihoodGivenDiagnosis;
            
            // Filter out very unlikely diagnoses to reduce computation
            if (newProbability > 0.001) {
                Diagnosis updatedDiagnosis = new Diagnosis(diagnosis.getComponents(), newProbability);
                updatedDiagnoses.add(updatedDiagnosis);
            }
        }
        
        // Renormalize probabilities to sum to 1.0
        double totalProb = updatedDiagnoses.stream().mapToDouble(Diagnosis::getProbability).sum();
        if (totalProb > 0) {
            for (Diagnosis d : updatedDiagnoses) {
                d.setProbability(d.getProbability() / totalProb);
            }
        } else {
            // Edge case: all diagnoses filtered out
            // Return uniform distribution over original diagnoses
            System.out.println("WARNING: All diagnoses filtered out during update. Using uniform distribution.");
            for (Diagnosis d : currentDiagnoses) {
                d.setProbability(1.0 / currentDiagnoses.size());
                updatedDiagnoses.add(d);
            }
        }
        
        return updatedDiagnoses;
    }
    
    /**
     * Simulate running a test and getting its result
     * In practice, this would actually execute the test
     * 
     * @param test The test to execute
     * @return Test result with pass/fail outcome and actual trace
     */
    public static TestResult simulateTestExecution(AvailableTest test) {
        Scanner scanner = new Scanner(System.in);
        System.out.printf("%n=== EXECUTE TEST: %s ===%n", test.getTestName());
        System.out.println("Estimated trace: " + test.getEstimatedTrace());
        System.out.print("Did this test PASS or FAIL? (p/f): ");
        
        String input = scanner.nextLine().trim().toLowerCase();
        boolean passed = input.startsWith("p");
        
        // For simulation, we'll use the estimated trace as actual trace
        // In real implementation, this would come from actual test execution
        return new TestResult(test.getTestName(), passed, test.getEstimatedTrace());
    }
    
    /**
     * Get comprehensive statistics about the current diagnosis state
     * 
     * @param diagnoses Current diagnoses
     * @return Statistics object with entropy, max probability, etc.
     */
    public DiagnosisStatistics getDiagnosisStatistics(List<Diagnosis> diagnoses) {
        return new DiagnosisStatistics(diagnoses);
    }
    
    /**
     * Debug method: Print goodness values for all components in a test trace
     * Useful for understanding why a test has certain probability estimates
     * 
     * @param test The test to analyze
     */
    public void printGoodnessDebugInfo(AvailableTest test) {
        System.out.println("\n=== GOODNESS DEBUG INFO ===");
        System.out.println("Test: " + test.getTestName());
        System.out.println("Components in trace:");
        
        for (String component : test.getEstimatedTrace()) {
            double goodness = calculateGoodness(component);
            EmbeddedSFL.ElemStats stats = componentStats.get(component);
            
            if (stats != null) {
                System.out.printf("  %-50s: goodness=%.3f (ef=%d, ep=%d, total=%d)%n",
                    component, goodness, stats.ef, stats.ep, stats.ef + stats.ep);
            } else {
                System.out.printf("  %-50s: goodness=%.3f (no stats)%n", component, goodness);
            }
        }
    }
}