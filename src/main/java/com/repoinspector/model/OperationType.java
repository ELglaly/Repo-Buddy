package com.repoinspector.model;

import java.util.Set;

/**
 * Classifies whether a repository method performs a read or write operation.
 */
public enum OperationType {
    READ,
    WRITE,
    UNKNOWN;

    private static final Set<String> WRITE_PREFIXES = Set.of(
            "save", "delete", "remove", "update", "create", "insert",
            "add", "put", "merge", "archive", "prune", "mark", "append",
            "flush", "persist", "store", "set"
    );

    public static OperationType fromMethodName(String methodName) {
        if (methodName == null || methodName.isEmpty()) return UNKNOWN;
        String lower = methodName.toLowerCase();
        for (String prefix : WRITE_PREFIXES) {
            if (lower.startsWith(prefix)) return WRITE;
        }
        return READ;
    }
}
