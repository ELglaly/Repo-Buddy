package com.repoinspector.analysis.api;

import com.intellij.psi.PsiMethod;
import com.repoinspector.model.EndpointInfo;

import java.util.List;

/**
 * Discovers Spring MVC REST endpoint methods in the project.
 *
 * <p>Retrieve via {@code project.getService(EndpointAnalysisService.class)}.
 */
public interface EndpointAnalysisService {

    /**
     * Finds all API endpoint methods annotated with Spring HTTP mapping annotations.
     * Safe to call from a background thread; wraps PSI access in a read action.
     */
    List<EndpointInfo> findAllEndpoints();

    /**
     * Returns {@code true} if {@code method} is annotated with any Spring HTTP
     * mapping annotation ({@code @GetMapping}, {@code @PostMapping}, etc.).
     * Must be called inside a read action.
     */
    boolean isEndpointMethod(PsiMethod method);
}
