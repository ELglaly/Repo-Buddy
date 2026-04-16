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

    /**
     * Classifies whether a repository method is a READ or WRITE operation.
     * Must be called inside a read action.
     */
    OperationType classify(PsiMethod method);

    /**
     * Extracts the entity type name from the repository's Spring Data generic supertype,
     * e.g. {@code UserRepository extends JpaRepository<User, Long>} returns {@code "User"}.
     * Must be called inside a read action.
     */
    String extractEntityName(PsiClass repoClass);

    /**
     * Returns {@code true} if the method or its containing class is annotated with
     * {@code @Transactional}. Must be called inside a read action.
     */
    boolean isTransactional(PsiMethod method);
}
