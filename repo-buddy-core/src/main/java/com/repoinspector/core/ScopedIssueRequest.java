package com.repoinspector.core;
public record ScopedIssueRequest(AnalysisScope scope, Severity severity, Severity minimumSeverity,
        String ruleId, String file, int limit, int offset, String scanId) {
    public ScopedIssueRequest {
        if (scope == null) throw new IllegalArgumentException("scope is required");
        if (ruleId != null && RepoBuddyRules.byId(ruleId) == null) throw new IllegalArgumentException("Unknown RepoBuddy rule: " + ruleId);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
    }
}
