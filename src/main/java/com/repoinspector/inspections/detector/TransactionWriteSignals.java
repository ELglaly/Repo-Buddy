package com.repoinspector.inspections.detector;

import java.util.Set;

/**
 * Pure (PSI-free) predicates identifying JPA write operations that require an
 * active transaction.
 */
public final class TransactionWriteSignals {

    private TransactionWriteSignals() {}

    /** {@code EntityManager}/{@code Query} methods that mutate and need a transaction. */
    private static final Set<String> JPA_WRITE_METHODS = Set.of(
            "persist", "merge", "remove", "flush", "executeUpdate"
    );

    private static final String[] PERSISTENCE_PACKAGES = {
            "jakarta.persistence.", "javax.persistence."
    };

    public static boolean isJpaWriteMethod(String methodName) {
        return methodName != null && JPA_WRITE_METHODS.contains(methodName);
    }

    /** True if the FQN belongs to the standard JPA API ({@code EntityManager}, {@code Query}, …). */
    public static boolean isJpaPersistenceType(String fqn) {
        if (fqn == null) return false;
        for (String pkg : PERSISTENCE_PACKAGES) {
            if (fqn.startsWith(pkg)) return true;
        }
        return false;
    }
}
