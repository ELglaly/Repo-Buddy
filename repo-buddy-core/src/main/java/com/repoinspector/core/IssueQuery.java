package com.repoinspector.core;
public record IssueQuery(Severity severity, Severity minimumSeverity, String ruleId, String file,
        int limit, int offset, String scanId) {
    public IssueQuery {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
    }
}
