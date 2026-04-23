package com.repoinspector.analysis.api;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.repoinspector.model.RepositoryMethodInfo;

import java.util.List;

/**
 * Discovers Spring repositories and analyses method call-site counts.
 */
public interface RepositoryAnalysisService {

    /**
     * Paired result of a full analysis pass.
     * {@code infos} and {@code methods} are parallel lists (same index → same method).
     */
    record AnalysisResult(List<RepositoryMethodInfo> infos, List<PsiMethod> methods) {
        public AnalysisResult {
            infos   = List.copyOf(infos);
            methods = List.copyOf(methods);
        }
    }

    /**
     * Runs a full analysis of all Spring repository methods in the project.
     * Safe to call from a background thread; wraps all PSI access in a read action.
     */
    AnalysisResult analyzeAll();

    /**
     * Returns {@code true} if {@code psiClass} is a Spring repository
     * (annotation-based or Spring Data hierarchy-based).
     */
    boolean isRepository(PsiClass psiClass);

    /**
     * Returns only the methods declared directly on {@code repoClass},
     * excluding inherited methods from Spring Data base interfaces.
     */
    List<PsiMethod> getRepositoryMethods(PsiClass repoClass);
}
