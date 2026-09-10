package com.repoinspector.core;
public record RepoBuddyIssue(String id, String ruleId, String category, Severity severity,
        String path, Integer line, Integer column, String message, String explanation,
        String suggestedFix, String affectedSymbol, IssueChangeStatus changeStatus,
        ChangeAttribution attribution) {
    public RepoBuddyIssue(String id, String ruleId, String category, Severity severity,
            String path, Integer line, Integer column, String message, String explanation,
            String suggestedFix) {
        this(id, ruleId, category, severity, path, line, column, message, explanation,
                suggestedFix, null, null, null);
    }
}
