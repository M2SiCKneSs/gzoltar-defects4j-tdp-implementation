package demo.gzoltar;

import java.util.*;
import java.util.stream.Collectors;
import com.gzoltar.sfl.formulas.Barinel;
import com.gzoltar.sfl.formulas.ISFLFormula;
import demo.gzoltar.TDPDataStructures.*;

/**
 * Corrected Hitting Set Computer that properly implements the TDP concept:
 * A diagnosis must be a hitting set that "hits" at least one component from every conflict.
 */
public class FilteredHittingSetComputer {
    
    private final ISFLFormula barinelFormula;
    private final ComponentFilter componentFilter;
    
    public FilteredHittingSetComputer() {
        this.barinelFormula = new Barinel();
        this.componentFilter = new ComponentFilter();
    }
    
    /**
     * Main diagnosis computation with proper hitting set logic
     */
    public List<Diagnosis> computeDiagnoses(List<Conflict> conflicts, 
                                          List<EmbeddedSFL.ElemStats> elementStats,
                                          int maxDiagnoses) {
        
        if (conflicts.isEmpty()) {
            System.out.println("No conflicts found - no failed tests");
            return new ArrayList<Diagnosis>();
        }
        
        System.out.println("Computing diagnoses from " + conflicts.size() + " conflicts...");
        displayConflicts(conflicts);
        
        // STEP 1: Filter conflicts to remove constructors
        List<Conflict> filteredConflicts = filterConflicts(conflicts, elementStats);
        System.out.println("After filtering: " + filteredConflicts.size() + " meaningful conflicts");
        displayConflicts(filteredConflicts);
        
        if (filteredConflicts.isEmpty()) {
            System.out.println("Warning: All conflicts filtered out - using fallback approach");
            return generateFallbackDiagnoses(conflicts, elementStats);
        }
        
        // STEP 2: Find minimal hitting sets 
        List<Set<String>> hittingSets = computeMinimalHittingSets(filteredConflicts, maxDiagnoses);
        System.out.println("Found " + hittingSets.size() + " minimal hitting sets");
        
        // STEP 3: Convert to diagnoses with probability assignment
        List<Diagnosis> diagnoses = new ArrayList<Diagnosis>();
        for (Set<String> hittingSet : hittingSets) {
            diagnoses.add(new Diagnosis(hittingSet));
        }
        
        // STEP 4: Assign probabilities using Barinel scores
        assignProbabilities(diagnoses, elementStats);
        
        // STEP 5: Sort by probability (highest first)
        Collections.sort(diagnoses, new Comparator<Diagnosis>() {
            public int compare(Diagnosis d1, Diagnosis d2) {
                return Double.compare(d2.getProbability(), d1.getProbability());
            }
        });
        
        // STEP 6: Validate and display results
        validateAndDisplayDiagnoses(diagnoses, filteredConflicts);
        
        return diagnoses;
    }
    
    /**
     * Display conflicts for debugging
     */
    private void displayConflicts(List<Conflict> conflicts) {
        for (int i = 0; i < conflicts.size(); i++) {
            Conflict c = conflicts.get(i);
            System.out.printf("  Conflict %d (%s): %s%n", 
                i + 1, c.getTestName(), c.getComponents());
        }
    }
    
    /**
     * CORE HITTING SET COMPUTATION - Fixed Logic
     */
    private List<Set<String>> computeMinimalHittingSets(List<Conflict> conflicts, int maxResults) {
        Set<String> allComponents = extractAllComponents(conflicts);
        System.out.println("All components in conflicts: " + allComponents.size());
        System.out.println("Components: " + allComponents);
        
        List<Set<String>> minimalHittingSets = new ArrayList<Set<String>>();
        
        // TRY 1: Single-component hitting sets (minimal)
        System.out.println("\n--- Searching for single-component hitting sets ---");
        for (String component : allComponents) {
            Set<String> singleComponent = Collections.singleton(component);
            if (isHittingSet(singleComponent, conflicts)) {
                minimalHittingSets.add(singleComponent);
                System.out.println("Found single-component hitting set: " + component);
            } else {
                System.out.println("" + component + " does not hit all conflicts");
                debugHittingSet(singleComponent, conflicts);
            }
        }
        
        // If we found single-component hitting sets, prefer them (they're minimal)
        if (!minimalHittingSets.isEmpty()) {
            System.out.println("Found " + minimalHittingSets.size() + " single-component hitting sets");
            return limitResults(minimalHittingSets, maxResults);
        }
        
        // TRY 2: Two-component hitting sets
        System.out.println("\n--- Searching for two-component hitting sets ---");
        List<String> componentList = new ArrayList<String>(allComponents);
        for (int i = 0; i < componentList.size() && minimalHittingSets.size() < maxResults; i++) {
            for (int j = i + 1; j < componentList.size() && minimalHittingSets.size() < maxResults; j++) {
                Set<String> twoComponents = new HashSet<String>();
                twoComponents.add(componentList.get(i));
                twoComponents.add(componentList.get(j));
                
                if (isHittingSet(twoComponents, conflicts)) {
                    minimalHittingSets.add(twoComponents);
                    System.out.println("✓ Found two-component hitting set: " + twoComponents);
                }
            }
        }
        
        if (!minimalHittingSets.isEmpty()) {
            return limitResults(minimalHittingSets, maxResults);
        }
        
        // TRY 3: Three-component hitting sets (last resort)
        System.out.println("\n--- Searching for three-component hitting sets ---");
        for (int i = 0; i < componentList.size() && minimalHittingSets.size() < 5; i++) {
            for (int j = i + 1; j < componentList.size() && minimalHittingSets.size() < 5; j++) {
                for (int k = j + 1; k < componentList.size() && minimalHittingSets.size() < 5; k++) {
                    Set<String> threeComponents = new HashSet<String>();
                    threeComponents.add(componentList.get(i));
                    threeComponents.add(componentList.get(j));
                    threeComponents.add(componentList.get(k));
                    
                    if (isHittingSet(threeComponents, conflicts)) {
                        minimalHittingSets.add(threeComponents);
                        System.out.println("✓ Found three-component hitting set: " + threeComponents);
                    }
                }
            }
        }
        
        return limitResults(minimalHittingSets, maxResults);
    }
    
    /**
     * Extract all unique components from conflicts
     */
    private Set<String> extractAllComponents(List<Conflict> conflicts) {
        Set<String> allComponents = new HashSet<String>();
        for (Conflict conflict : conflicts) {
            allComponents.addAll(conflict.getComponents());
        }
        return allComponents;
    }
    
    /**
     * Check if a set of components is a hitting set
     * CORE LOGIC: Must hit (contain at least one component from) every conflict
     */
    private boolean isHittingSet(Set<String> proposedDiagnosis, List<Conflict> conflicts) {
        for (Conflict conflict : conflicts) {
            boolean hitsThisConflict = false;
            
            // Check if proposed diagnosis contains any component from this conflict
            for (String component : proposedDiagnosis) {
                if (conflict.getComponents().contains(component)) {
                    hitsThisConflict = true;
                    break;
                }
            }
            
            if (!hitsThisConflict) {
                return false; // This diagnosis cannot explain this failed test
            }
        }
        return true; // This diagnosis can explain all failed tests
    }
    
    /**
     * Debug why a hitting set doesn't work
     */
    private void debugHittingSet(Set<String> components, List<Conflict> conflicts) {
        for (int i = 0; i < conflicts.size(); i++) {
            Conflict conflict = conflicts.get(i);
            boolean hits = false;
            for (String component : components) {
                if (conflict.getComponents().contains(component)) {
                    hits = true;
                    break;
                }
            }
            if (!hits) {
                System.out.println("    Does not hit conflict " + (i + 1) + " (" + conflict.getTestName() + ")");
                System.out.println("    Conflict components: " + conflict.getComponents());
            }
        }
    }
    
    /**
     * Limit results to specified maximum
     */
    private List<Set<String>> limitResults(List<Set<String>> results, int maxResults) {
        if (results.size() <= maxResults) {
            return results;
        }
        return new ArrayList<Set<String>>(results.subList(0, maxResults));
    }
    
    /**
     * Filter conflicts to remove constructors and common components
     */
    private List<Conflict> filterConflicts(List<Conflict> originalConflicts, 
                                         List<EmbeddedSFL.ElemStats> elementStats) {
        
        System.out.println("Filtering conflicts to remove constructors...");
        List<Conflict> filteredConflicts = new ArrayList<Conflict>();
        
        for (Conflict conflict : originalConflicts) {
            Set<String> originalComponents = conflict.getComponents();
            Set<String> filteredComponents = new HashSet<String>();
            
            for (String component : originalComponents) {
                if (componentFilter.shouldIncludeInDiagnosis(component, elementStats)) {
                    filteredComponents.add(component);
                }
            }
            
            if (!filteredComponents.isEmpty()) {
                Conflict filteredConflict = new Conflict(filteredComponents, conflict.getTestName());
                filteredConflicts.add(filteredConflict);
                
                // Log what was filtered out
                Set<String> removedComponents = new HashSet<String>(originalComponents);
                removedComponents.removeAll(filteredComponents);
                if (!removedComponents.isEmpty()) {
                    System.out.println("  Filtered from " + conflict.getTestName() + ": " + removedComponents);
                }
            }
        }
        
        return filteredConflicts;
    }
    
    /**
     * Component Filter - same as before
     */
    private static class ComponentFilter {
        
        public boolean shouldIncludeInDiagnosis(String component, List<EmbeddedSFL.ElemStats> elementStats) {
            if (isConstructor(component)) return false;
            if (isOverlyCommon(component, elementStats)) return false;
            if (isFrameworkMethod(component)) return false;
            if (hasZeroSuspiciousness(component, elementStats)) return false;
            return true;
        }
        
        private boolean isConstructor(String component) {
            return component.contains("#<init>") ||
                   component.contains("#<clinit>") ||
                   component.contains("#Constructor") ||
                   (component.contains("()") && component.matches(".*#[A-Z][a-zA-Z]*\\(\\).*"));
        }
        
        private boolean isOverlyCommon(String component, List<EmbeddedSFL.ElemStats> elementStats) {
            EmbeddedSFL.ElemStats stats = findStats(component, elementStats);
            if (stats == null) return false;
            
            int totalTests = stats.ef + stats.ep + stats.nf + stats.np;
            int coveredTests = stats.ef + stats.ep;
            
            if (totalTests == 0) return true;
            
            double coverageRatio = (double) coveredTests / totalTests;
            return coverageRatio > 0.8;
        }
        
        private boolean isFrameworkMethod(String component) {
            return component.contains("#toString") ||
                   component.contains("#equals") ||
                   component.contains("#hashCode") ||
                   component.contains("#clone") ||
                   component.contains("java.lang.") ||
                   component.contains("junit.");
        }
        
        private boolean hasZeroSuspiciousness(String component, List<EmbeddedSFL.ElemStats> elementStats) {
            EmbeddedSFL.ElemStats stats = findStats(component, elementStats);
            if (stats == null) return true;
            return stats.ef == 0;
        }
        
        private EmbeddedSFL.ElemStats findStats(String component, List<EmbeddedSFL.ElemStats> elementStats) {
            for (EmbeddedSFL.ElemStats stats : elementStats) {
                if (stats.id.equals(component)) {
                    return stats;
                }
            }
            return null;
        }
    }
    
    /**
     * Assign probabilities using Barinel scores
     */
    private void assignProbabilities(List<Diagnosis> diagnoses, List<EmbeddedSFL.ElemStats> elementStats) {
        Map<String, Double> barinelScores = new HashMap<String, Double>();
        
        for (EmbeddedSFL.ElemStats stat : elementStats) {
            double score = barinelFormula.compute(stat.np, stat.nf, stat.ep, stat.ef);
            barinelScores.put(stat.id, score);
        }
        
        for (Diagnosis diagnosis : diagnoses) {
            double score = computeDiagnosisScore(diagnosis, barinelScores);
            diagnosis.setProbability(score);
        }
        
        // Normalize probabilities
        double totalScore = 0.0;
        for (Diagnosis d : diagnoses) {
            totalScore += d.getProbability();
        }
        
        if (totalScore > 0) {
            for (Diagnosis d : diagnoses) {
                d.setProbability(d.getProbability() / totalScore);
            }
        } else {
            double uniformProb = 1.0 / diagnoses.size();
            for (Diagnosis d : diagnoses) {
                d.setProbability(uniformProb);
            }
        }
    }
    
    /**
     * Compute diagnosis score with preference for smaller diagnoses (Occam's razor)
     */
    private double computeDiagnosisScore(Diagnosis diagnosis, Map<String, Double> barinelScores) {
        double totalScore = 0.0;
        int componentCount = 0;
        
        for (String comp : diagnosis.getComponents()) {
            Double score = barinelScores.get(comp);
            if (score != null) {
                totalScore += score;
                componentCount++;
            }
        }
        
        double avgScore = componentCount > 0 ? totalScore / componentCount : 0.0;
        
        // Strong preference for smaller diagnoses (Occam's razor)
        double sizePenalty = Math.pow(0.5, diagnosis.size() - 1);
        
        return avgScore * sizePenalty;
    }
    
    /**
     * Validate and display diagnoses
     */
    private void validateAndDisplayDiagnoses(List<Diagnosis> diagnoses, List<Conflict> conflicts) {
        System.out.println("\n=== DIAGNOSIS VALIDATION ===");
        System.out.println("Generated " + diagnoses.size() + " diagnoses");
        
        for (int i = 0; i < Math.min(5, diagnoses.size()); i++) {
            Diagnosis d = diagnoses.get(i);
            System.out.printf("Diagnosis %d (prob=%.3f, size=%d): %s%n", 
                i + 1, d.getProbability(), d.size(), d.getComponents());
            
            // Verify it's actually a hitting set
            boolean isValid = isHittingSet(d.getComponents(), conflicts);
            System.out.println("  Valid hitting set: " + (isValid ? "Yes" : "No"));
            
            if (!isValid) {
                debugHittingSet(d.getComponents(), conflicts);
            }
        }
    }
    
    /**
     * Generate fallback diagnoses if filtering removed everything
     */
    private List<Diagnosis> generateFallbackDiagnoses(List<Conflict> originalConflicts,
                                                    List<EmbeddedSFL.ElemStats> elementStats) {
        System.out.println("Generating fallback diagnoses...");
        
        // Use most suspicious non-constructor components
        List<EmbeddedSFL.ElemStats> filteredStats = new ArrayList<EmbeddedSFL.ElemStats>();
        for (EmbeddedSFL.ElemStats stats : elementStats) {
            if (!componentFilter.isConstructor(stats.id)) {
                filteredStats.add(stats);
            }
        }
        
        Collections.sort(filteredStats, new Comparator<EmbeddedSFL.ElemStats>() {
            public int compare(EmbeddedSFL.ElemStats s1, EmbeddedSFL.ElemStats s2) {
                double score1 = barinelFormula.compute(s1.np, s1.nf, s1.ep, s1.ef);
                double score2 = barinelFormula.compute(s2.np, s2.nf, s2.ep, s2.ef);
                return Double.compare(score2, score1);
            }
        });
        
        List<Diagnosis> fallbackDiagnoses = new ArrayList<Diagnosis>();
        for (int i = 0; i < Math.min(3, filteredStats.size()); i++) {
            String component = filteredStats.get(i).id;
            Set<String> singleComponent = Collections.singleton(component);
            fallbackDiagnoses.add(new Diagnosis(singleComponent, 1.0 / Math.min(3, filteredStats.size())));
        }
        
        return fallbackDiagnoses;
    }
    
    /**
     * Extract conflicts from failed tests
     */
    public static List<Conflict> extractConflicts(List<EmbeddedSFL.TestCase> testCases,
                                                boolean[][] coverage,
                                                List<String> elements) {
        List<Conflict> conflicts = new ArrayList<Conflict>();
        
        for (int i = 0; i < testCases.size(); i++) {
            EmbeddedSFL.TestCase testCase = testCases.get(i);
            if (testCase.failed) {
                Set<String> conflictComponents = new HashSet<String>();
                
                for (int j = 0; j < elements.size() && j < coverage[i].length; j++) {
                    if (coverage[i][j]) {
                        conflictComponents.add(elements.get(j));
                    }
                }
                
                if (!conflictComponents.isEmpty()) {
                    conflicts.add(new Conflict(conflictComponents, testCase.name));
                }
            }
        }
        
        System.out.println("Extracted " + conflicts.size() + " conflicts from failed tests");
        return conflicts;
    }
}