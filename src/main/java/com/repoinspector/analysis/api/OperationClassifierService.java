package com.repoinspector.analysis.api;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.repoinspector.model.OperationType;

/**
 * Classifies repository methods as READ or WRITE and extracts entity type information.
 *
 * <p>Application-level service (stateless).
 * Retrieve via {@code ApplicationManager.getApplication().getService(OperationClassifierService.class)}.
 */
public interface OperationClassifierService {

    OperationType classify(PsiMethod method);

    String extractEntityName(PsiClass repoClass);

    boolean isTransactional(PsiMethod method);
}
