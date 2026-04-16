package com.repoinspector.runner.service.api;

import com.intellij.psi.PsiMethod;
import com.repoinspector.runner.model.ParameterDef;

import java.util.List;

/**
 * Extracts parameter descriptors from PSI methods and builds method signatures.
 *
 * <p>Application-level service (stateless).
 * Retrieve via {@code ApplicationManager.getApplication().getService(ParameterExtractionService.class)}.
 */
public interface ParameterExtractionService {

    /**
     * Returns one {@link ParameterDef} per parameter declared by {@code method}, in declaration order.
     * Must be called inside a read action.
     */
    List<ParameterDef> extract(PsiMethod method);

    /**
     * Builds a compact human-readable parameter summary,
     * e.g. {@code "id: Long, name: String"} or {@code "no params"}.
     * Must be called inside a read action.
     */
    String summary(PsiMethod method);

    /**
     * Builds a human-readable method signature string, e.g. {@code findById(Long)}.
     * Must be called inside a read action.
     */
    String buildSignature(PsiMethod method);
}
