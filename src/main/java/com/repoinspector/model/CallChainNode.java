package com.repoinspector.model;

import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.Nullable;

/**
 * Represents one method in the call chain traced from an API endpoint.
 *
 * @param className           Simple name of the containing class
 * @param methodSignature     Human-readable signature, e.g. "findById(Long)"
 * @param depth               Call depth from the endpoint (0 = endpoint itself)
 * @param isRepository        True if this method belongs to a Spring repository
 * @param operationType       READ/WRITE/UNKNOWN — meaningful only when isRepository is true
 * @param entityName          Generic entity type, e.g. "User" — empty string if not a repository node
 * @param isTransactional     True if the method or its declaring class has @Transactional
 * @param isDynamic           True if reached via ApplicationContext.getBean() — cannot be statically traced
 * @param qualifiedClassName  FQN of the containing class; used to re-resolve PSI for navigation
 * @param psiMethod           Live PSI reference for immediate navigation; null in cached/stored copies
 */
public record CallChainNode(
        String className,
        String methodSignature,
        int depth,
        boolean isRepository,
        OperationType operationType,
        String entityName,
        boolean isTransactional,
        boolean isDynamic,
        @Nullable String qualifiedClassName,
        @Nullable PsiMethod psiMethod
) {
    /** Returns a copy of this node with the PSI reference stripped for safe long-term storage. */
    public CallChainNode withoutPsi() {
        return new CallChainNode(className, methodSignature, depth, isRepository,
                operationType, entityName, isTransactional, isDynamic, qualifiedClassName, null);
    }

    public String displayLabel() {
        if (isDynamic) {
            return "[DYNAMIC - cannot trace]  " + className + "." + methodSignature;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(className).append('.').append(methodSignature);
        if (isRepository) {
            sb.append("  [").append(operationType.name()).append(']');
            if (!entityName.isEmpty()) {
                sb.append(" entity=").append(entityName);
            }
        }
        if (isTransactional) {
            sb.append("  [@Transactional]");
        }
        return sb.toString();
    }
}
