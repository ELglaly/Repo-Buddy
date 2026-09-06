package com.repoinspector.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IssueFilters {
    private IssueFilters() {}

    public static List<RepoBuddyIssue> filter(List<RepoBuddyIssue> source, IssueQuery query) {
        List<RepoBuddyIssue> filtered = source.stream().filter(issue -> matches(issue, query)).toList();
        int from = Math.min(query.offset(), filtered.size());
        int to = Math.min(from + query.limit(), filtered.size());
        return new ArrayList<>(filtered.subList(from, to));
    }

    public static int count(List<RepoBuddyIssue> source, IssueQuery query) {
        return (int) source.stream().filter(issue -> matches(issue, query)).count();
    }

    public static RepoBuddyScanSummary summarize(List<RepoBuddyIssue> issues) {
        Map<Severity, Integer> severity = new EnumMap<>(Severity.class);
        for (Severity value : Severity.values()) severity.put(value, 0);
        Map<String, Integer> rules = new LinkedHashMap<>();
        for (RepoBuddyIssue issue : issues) {
            severity.merge(issue.severity(), 1, Integer::sum);
            rules.merge(issue.ruleId(), 1, Integer::sum);
        }
        return new RepoBuddyScanSummary(issues.size(), severity, rules);
    }

    private static boolean matches(RepoBuddyIssue issue, IssueQuery query) {
        if (query.severity() != null && issue.severity() != query.severity()) return false;
        if (query.minimumSeverity() != null && !issue.severity().atLeast(query.minimumSeverity())) return false;
        if (query.ruleId() != null && !issue.ruleId().equalsIgnoreCase(query.ruleId())) return false;
        if (query.file() != null) {
            String wanted = IssueIds.normalizePath(query.file()).toLowerCase(Locale.ROOT);
            if (!issue.path().toLowerCase(Locale.ROOT).equals(wanted)) return false;
        }
        return true;
    }
}
