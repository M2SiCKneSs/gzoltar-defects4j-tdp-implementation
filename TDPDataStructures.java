package demo.gzoltar;

import java.util.*;

/**
 * Data structures used by the TDP algorithm
 */
public class TDPDataStructures {
    
    /**
     * Represents a single diagnosis - a set of components that could explain all failures
     */
    public static class Diagnosis {
        private final Set<String> components;
        private double probability;
        
        public Diagnosis(Set<String> components) {
            this.components = new HashSet<>(components);
            this.probability = 0.0;
        }
        
        public Diagnosis(Set<String> components, double probability) {
            this.components = new HashSet<>(components);
            this.probability = probability;
        }
        
        public Set<String> getComponents() {
            return new HashSet<>(components);
        }
        
        public double getProbability() {
            return probability;
        }
        
        public void setProbability(double probability) {
            this.probability = probability;
        }
        
        public int size() {
            return components.size();
        }
        
        public boolean contains(String component) {
            return components.contains(component);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Diagnosis)) return false;
            Diagnosis other = (Diagnosis) obj;
            return components.equals(other.components);
        }
        
        @Override
        public int hashCode() {
            return components.hashCode();
        }
        
        @Override
        public String toString() {
            return String.format("Diagnosis{components=%s, prob=%.3f}", components, probability);
        }
    }
    
    /**
     * Represents a conflict - a set of components covered by a failed test
     */
    public static class Conflict {
        private final Set<String> components;
        private final String testName;
        
        public Conflict(Set<String> components, String testName) {
            this.components = new HashSet<>(components);
            this.testName = testName;
        }
        
        public Set<String> getComponents() {
            return new HashSet<>(components);
        }
        
        public String getTestName() {
            return testName;
        }
        
        @Override
        public String toString() {
            return String.format("Conflict{test=%s, components=%s}", testName, components);
        }
    }
    
    /**
     * Represents information about a test that could be executed
     */
    public static class AvailableTest {
        private final String testName;
        private final Set<String> estimatedTrace;
        private final double estimatedPassProbability;
        
        public AvailableTest(String testName, Set<String> estimatedTrace) {
            this.testName = testName;
            this.estimatedTrace = new HashSet<>(estimatedTrace);
            this.estimatedPassProbability = 0.5; // Default assumption
        }
        
        public AvailableTest(String testName, Set<String> estimatedTrace, double passProb) {
            this.testName = testName;
            this.estimatedTrace = new HashSet<>(estimatedTrace);
            this.estimatedPassProbability = passProb;
        }
        
        public String getTestName() {
            return testName;
        }
        
        public Set<String> getEstimatedTrace() {
            return new HashSet<>(estimatedTrace);
        }
        
        public double getEstimatedPassProbability() {
            return estimatedPassProbability;
        }
        
        @Override
        public String toString() {
            return String.format("AvailableTest{name=%s, trace=%s}", testName, estimatedTrace);
        }
    }
    
    /**
     * Result of executing a test
     */
    public static class TestResult {
        private final String testName;
        private final boolean passed;
        private final Set<String> actualTrace;
        
        public TestResult(String testName, boolean passed, Set<String> actualTrace) {
            this.testName = testName;
            this.passed = passed;
            this.actualTrace = new HashSet<>(actualTrace);
        }
        
        public String getTestName() {
            return testName;
        }
        
        public boolean isPassed() {
            return passed;
        }
        
        public boolean isFailed() {
            return !passed;
        }
        
        public Set<String> getActualTrace() {
            return new HashSet<>(actualTrace);
        }
        
        @Override
        public String toString() {
            return String.format("TestResult{name=%s, passed=%s, trace=%s}", 
                               testName, passed, actualTrace);
        }
    }
    // Add to TDPDataStructures.java
    public static class TestInfo {
        private final String testName;
        private final Set<String> trace;
        private final Boolean outcome; // null if not yet executed
        
        public TestInfo(String testName, Set<String> trace, Boolean outcome) {
            this.testName = testName;
            this.trace = new HashSet<>(trace);
            this.outcome = outcome;
        }
        
        public String getTestName() { return testName; }
        public Set<String> getTrace() { return new HashSet<>(trace); }
        public Boolean getOutcome() { return outcome; }
        public boolean isExecuted() { return outcome != null; }
    }
    
    /**
     * Statistics for entropy and information gain calculations
     */
    public static class DiagnosisStatistics {
        private final List<Diagnosis> diagnoses;
        private final double entropy;
        private final int totalDiagnoses;
        private final double maxProbability;
        private final Diagnosis mostLikelyDiagnosis;
        
        public DiagnosisStatistics(List<Diagnosis> diagnoses) {
            this.diagnoses = new ArrayList<>(diagnoses);
            this.totalDiagnoses = diagnoses.size();
            
            // Calculate entropy: -∑ p(Δᵢ) × log(p(Δᵢ))
            double entropySum = 0.0;
            double maxProb = 0.0;
            Diagnosis bestDiagnosis = null;
            
            for (Diagnosis d : diagnoses) {
                if (d.getProbability() > 0) {
                    entropySum -= d.getProbability() * Math.log(d.getProbability());
                }
                if (d.getProbability() > maxProb) {
                    maxProb = d.getProbability();
                    bestDiagnosis = d;
                }
            }
            
            this.entropy = entropySum;
            this.maxProbability = maxProb;
            this.mostLikelyDiagnosis = bestDiagnosis;
        }
        
        public List<Diagnosis> getDiagnoses() {
            return new ArrayList<>(diagnoses);
        }
        
        public double getEntropy() {
            return entropy;
        }
        
        public int getTotalDiagnoses() {
            return totalDiagnoses;
        }
        
        public double getMaxProbability() {
            return maxProbability;
        }
        
        public Diagnosis getMostLikelyDiagnosis() {
            return mostLikelyDiagnosis;
        }
        
        public boolean isComplete() {
            return totalDiagnoses == 1 || maxProbability > 0.9;
        }
        
        @Override
        public String toString() {
            return String.format("DiagnosisStats{count=%d, entropy=%.3f, maxProb=%.3f, complete=%s}", 
                               totalDiagnoses, entropy, maxProbability, isComplete());
        }
    }
}