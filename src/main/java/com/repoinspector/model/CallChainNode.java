package com.repoinspector.model;

import com.intellij.psi.PsiMethod;

/**
 * Represents one method in the call chain traced from an API endpoint.
 *
 * @param className       Simple name of the containing class
 * @param methodSignature Human-readable signature, e.g. "findById(Long)"
 * @param depth           Call depth from the endpoint (0 = endpoint itself)
 * @param isRepository    True if this method belongs to a Spring repository
 * @param operationType   READ/WRITE/UNKNOWN — meaningful only when isRepository is true
 * @param entityName      Generic entity type, e.g. "User" — empty string if not a repository node
 * @param isTransactional True if the method or its declaring class has @Transactional
 * @param isDynamic       True if reached via ApplicationContext.getBean() — cannot be statically traced
 * @param psiMethod       PSI reference for navigation; null when isDynamic is true
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
        PsiMethod psiMethod
) {
    /** Convenience label used in the tree and the text export. */
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
